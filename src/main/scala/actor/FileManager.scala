package org.jerchung.torrent.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.util.ByteString
import akka.util.Timeout
import com.escalatesoft.subcut.inject._
import com.twitter.util.LruMap
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.jerchung.torrent.actor.message.BT
import org.jerchung.torrent.actor.message.FM._
import org.jerchung.torrent.actor.message.FW
import org.jerchung.torrent.actor.message.PeerM
import org.jerchung.torrent.actor.persist.MultiFileWorker
import org.jerchung.torrent.actor.persist.PieceWorker
import org.jerchung.torrent.actor.persist.SingleFileWorker
import org.jerchung.torrent.dependency.BindingKeys._
import org.jerchung.torrent.piece._
import org.jerchung.torrent.Torrent
import org.jerchung.torrent.{ Single, Multi }
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success

object FileManager {
  def props(torrent: Torrent)(implicit bindingModule: BindingModule): Props = {
    Props(new FileManager(torrent))
  }

  // Messages to be used internally between FileManager and Storage worker
  // the offset specified here is the total offset within all the files
  // calculated from index * pieceSize, not the offset within the piece itself
  object FileWorker {
    case class Read(totalOffset: Int, length: Int)
    case class Write(totalOffset: Int, block: ByteString)
  }

  case class BlockRequest(peer: ActorRef, offset: Int, length: Int)
}

/**
 * Doesn't just manage files, manages pieces, blocks etc. but I couldn't really
 * think of a better name for this actor.
 *
 * Parent of this actor *should* be TorrentClient
 *
 * @torrent Torrent object passed in since many values
 */
class FileManager(torrent: Torrent) extends Actor with AutoInjectable {

  import FileManager._
  import PieceWorker._
  import context.dispatcher
  import context.system

  val FileWriteTimeout = 1 minutes
  val CacheSize = 10

  // Important values
  val numPieces   = torrent.numPieces
  val pieceSize   = torrent.pieceSize
  val totalSize   = torrent.totalSize
  val piecesHash  = torrent.piecesHash

  // Cache map for quick piece access (pieceIndex -> Piece)
  // is LRU since it's not feasible to store ALL pieces in memory
  val cachedPieces = new LruMap[Int, Array[Byte]](CacheSize)

  // Another cache for pieces in the process of being written to disk
  // Pieces are removed from here once they are
  // completely written to disk
  var flushingPieces = Map[Int, Array[Byte]]()

  // pieceIndex -> BlockRequest
  var queuedRequests = Map[Int, Set[BlockRequest]]()
                       .withDefaultValue(Set[BlockRequest]())

  // Actor that takes care of reading / writing from disk
  val fileWorker = injectOptional [ActorRef](FileWorkerId) getOrElse {
    torrent.fileMode match {
      case Single =>
        context.actorOf(SingleFileWorker.props(
          torrent.name,
          pieceSize,
          totalSize
        ))
      case Multi =>
        context.actorOf(MultiFileWorker.props(torrent.files, pieceSize))
    }
  }

  // Array of actors which will write blocks to pieces (index -> ActorRef)
  val pieceWorkers: Array[ActorRef] = {
    val pieceHashesGrouped = piecesHash.grouped(20).toList

    pieceHashesGrouped.foldLeft((mutable.ArrayBuffer[ActorRef](), 0, 0)) {
      case ((pieceWorkers, offset, idx), hash) =>
        val size = pieceSize min (totalSize - offset)
        val piece = Piece(idx, idx * pieceSize, size, hash)
        val pieceWorker = createPieceWorker(fileWorker, idx, piece)
        (pieceWorkers += pieceWorker, offset + size, idx + 1)
    }._1.toArray
  }

  // All pieces start out in unfinished state
  val pieceStates: Array[PieceState] = Array.fill(numPieces) { Unfinished }

  def receive = {

    // Piece is already cached :)
    case Read(idx, off, length) if ((cachedPieces contains idx) ||
                                    (flushingPieces contains idx)) =>
      val peer = sender
      val data = cachedPieces.getOrElse(idx, flushingPieces(idx))
      Future {
        val block = ByteString.fromArray(data, off, length)
        peer ! BT.Piece(idx, off, block)
      }

    // Piece is not cached :(
    case msg @ Read(idx, off, length) =>
      pieceStates(idx) match {
        case Disk =>
          pieceWorkers(idx) ! msg
          queueRequest(sender, idx, off, length)
          pieceStates(idx) = Fetching
        case Fetching =>
          queueRequest(sender, idx, off, length)
        case _ => // Do nothing (should not get here)
      }

    case Write(idx, off, block) =>
      pieceStates(idx) match {
        case Unfinished =>
          pieceWorkers(idx) ! BlockWrite(off, block, sender)
        case _ => // Should never receive write request for finished piece
      }

    // If piece is fully filled, will return data in form of Option[Array[Byte]],
    // else will be None.  Use this Option to try to write the data to disk.
    // Upon notification of complete write to disk, then move piece to Lru and
    // set piece state to Disk.  Do nothing if PieceState is still at Unfinished
    case BlockWriteDone(idx, totalOffset, state, peer, dataOption) =>
      state match {
        case Done =>
          pieceStates(idx) = Done
          dataOption foreach { data =>
            writePiece(idx, totalOffset, data)
            flushingPieces += (idx -> data)
            cachedPieces += (idx -> data)
          }
          peer ! PeerM.PieceDone(idx)
          sender ! ClearPieceData
        case Invalid =>
          peer ! PeerM.PieceInvalid(idx)
          sender ! ClearPieceData
        case _ =>
      }

    case FW.ReadDone(idx, piece) =>
      pieceStates(idx) = Disk
      cachedPieces += (idx -> piece)
      val requests = queuedRequests(idx)
      queuedRequests -= idx
      Future { fulfillRequests(idx, piece, requests) }

    case FW.WriteDone(idx) =>
      flushingPieces -= idx
      pieceStates(idx) = Disk

  }

  def writePiece(index: Int, offset: Int, piece: Array[Byte]): Unit = {
    implicit val timeout = Timeout(FileWriteTimeout)
    val numTries = 3

    def tryWrite(count: Int): Unit = {
      val writeF = (fileWorker ? FW.Write(index, offset, piece)).mapTo[FW.WriteDone]
      writeF onComplete {
        case Success(writeDoneMsg) =>
          self ! writeDoneMsg
        case Failure(e) =>
          if (count < numTries) tryWrite(count + 1)
          else self ! e
      }
    }

    tryWrite(0)
  }

  // Update queued requests by adding to existing set of requests
  def queueRequest(peer: ActorRef, index: Int, offset: Int, length: Int): Unit = {
    val request = BlockRequest(peer, offset, length)
    queuedRequests += (index -> (queuedRequests(index) + request))
  }

  // Fulfill all the queued requests for that particular piece
  def fulfillRequests(
      index: Int,
      piece: Array[Byte],
      requests: Set[BlockRequest]): Unit = {
    requests foreach { case BlockRequest(peer, offset, length) =>
      val bytes = ByteString.fromArray(piece, offset, length)
      peer ! BT.Piece(index, offset, bytes)
    }
  }

  def createPieceWorker(worker: ActorRef, index: Int, piece: Piece): ActorRef = {
    injectOptional [ActorRef](PieceWorkerId) getOrElse {
      context.actorOf(PieceWorker.props(worker, index, piece))
    }
  }

}