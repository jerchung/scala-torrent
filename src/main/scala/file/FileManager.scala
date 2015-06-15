package storrent.file

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.util.ByteString
import akka.util.Timeout
import com.twitter.util.LruMap
import java.nio.ByteBuffer
import java.security.MessageDigest
import storrent.message.BT
import storrent.message.FM._
import storrent.message.PeerM
import storrent.file._
import storrent.Torrent
import storrent.{ Single, Multi }
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success

object FileManager {
  def props(torrent: Torrent, folder: String): Props = {
    Props(new FileManager(torrent, folder) with AppCake)
  }

  trait Cake { this: FileManager =>
    def provider: Provider
    def requestManager: ActorRef
    trait Provider {
      def fileWorker(torrent: Torrent, folder: String): ActorRef
      def pieceWorker(worker: ActorRef, index: Int, piece: Piece): ActorRef
    }
  }

  trait AppCake extends Cake { this: FileManager =>
    val requestManager = context.actorOf(RequestManager.props)
    override object provider extends Provider {
      def fileWorker(torrent: Torrent, folder: String) = torrent.fileMode match {
        case Single =>
          context.actorOf(SingleFileWorker.props(
            s"$folder/${torrent.name}",
            torrent.pieceSize
          ))
        case Multi =>
          context.actorOf(MultiFileWorker.props(
            torrent.files,
            pieceSize,
            folder
          ))
      }

      def pieceWorker(worker: ActorRef, index: Int, piece: Piece) =
        context.actorOf(PieceWorker.props(worker, index, piece))
    }
  }

  // Messages to be used internally between FileManager and Storage worker
  // the offset specified here is the total offset within all the files
  // calculated from index * pieceSize, not the offset within the piece itself
  object FileWorker {
    case class Read(index: Int, totalOffset: Int, length: Int)
    case class Write(index: Int, totalOffset: Int, block: ByteString)
    case class ReadDone(index: Int, piece: Array[Byte])
    case class WriteDone(index: Int)
  }

  case class BlockRequest(peer: ActorRef, offset: Int, length: Int)

  object RequestManager {
    def props(): Props = {
      Props(new RequestManager)
    }

    case class Fulfill(index: Int, piece: Array[Byte])
    case class Queue(peer: ActorRef, index: Int, offset: Int, length: Int)
  }

  // Actor to reply to peers with requested parts of pieces once the piece
  // has come back from disk
  class RequestManager extends Actor {

    import RequestManager._

    var queuedRequests = Map[Int, Set[BlockRequest]]().withDefaultValue(Set[BlockRequest]())

    def receive = {
      case Queue(peer, idx, off, length) =>
        val request = BlockRequest(peer, off, length)
        queuedRequests += (idx -> (queuedRequests(idx) + request))

      case Fulfill(idx, piece) =>
        val requests = queuedRequests(idx)
        queuedRequests -= idx
        requests foreach { case BlockRequest(peer, offset, length) =>
          val bytes = ByteString.fromArray(piece, offset, length)
          peer ! BT.Piece(idx, offset, bytes)
        }
    }
  }

  // Cannot write to disk for some reason, return index of the piece in question
  // and a message
  case class DiskWriteException(
    index: Int,
    message: String)
    extends Exception(message)
}

/**
 * Doesn't just manage files, manages pieces, blocks etc. but I couldn't really
 * think of a better name for this actor.
 *
 * Parent of this actor *should* be TorrentClient
 *
 * @torrent Torrent object passed in since it has many values
 */
class FileManager(torrent: Torrent, folder: String) extends Actor { this: FileManager.Cake =>

  import FileManager._
  import FileManager.{FileWorker => FW}
  import PieceWorker._
  import RequestManager._
  import context.dispatcher
  import context.system

  val FileWriteTimeout = 1 minutes
  val FileWriteTries = 3
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

  // Actor that takes care of reading / writing from disk
  val fileWorker = provider.fileWorker(torrent, folder)

  // Array of actors which will write blocks to pieces
  // Index of actorRef within array corresponds to piece it's working on
  val pieceWorkers: Array[ActorRef] = {
    val pieceHashesGrouped = piecesHash.grouped(20).toList

    pieceHashesGrouped.foldLeft((mutable.ArrayBuffer[ActorRef](), 0, 0)) {
      case ((pieceWorkers, offset, idx), hash) =>
        val size = pieceSize min (totalSize - offset)
        val piece = Piece(idx, idx * pieceSize, size, hash)
        val pieceWorker = provider.pieceWorker(fileWorker, idx, piece)
        (pieceWorkers += pieceWorker, offset + size, idx + 1)
    }._1.toArray
  }

  // All pieces start out in unfinished state
  val pieceStates: Array[PieceState] = Array.fill(numPieces) { Unfinished }

  def receive = {

    // Piece is already cached :)
    case Read(idx, off, length) if ((cachedPieces contains idx) ||
                                    (flushingPieces contains idx)) =>
      val data = cachedPieces.getOrElse(idx, flushingPieces(idx))
      val block = ByteString.fromArray(data, off, length)
      sender ! BT.Piece(idx, off, block)

    // Piece is not cached :(
    case msg @ Read(idx, off, length) =>
      pieceStates(idx) match {
        case Disk =>
          requestManager ! Queue(sender, idx, off, length)
          pieceWorkers(idx) ! msg
          pieceStates(idx) = Fetching
        case Fetching =>
          requestManager ! Queue(sender, idx, off, length)
        case _ => // Do nothing (should not get here)
      }

    case Write(idx, off, block) =>
      pieceStates(idx) match {
        case Unfinished => pieceWorkers(idx) ! BlockWrite(off, block, sender)
        case _ => // Should never receive write request for finished piece
      }

    // If piece is complete, will return data in form of Option[Array[Byte]],
    // else will be None.  Use this Option to try to write the data to disk.
    // Upon notification of complete write to disk, then move piece to Lru and
    // set piece state to Disk.  Do nothing if PieceState is still at Unfinished
    case BlockWriteDone(idx, totalOffset, state, peer, dataOption) =>
      state match {
        case Done =>
          pieceStates(idx) = Done
          dataOption foreach { data =>
            fileWorker ! FW.Write(idx, totalOffset, ByteString.fromArray(data))
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
      requestManager ! Fulfill(idx, piece)

    case FW.WriteDone(idx) =>
      flushingPieces -= idx
      pieceStates(idx) = Disk

    case e @ DiskWriteException(idx, msg) =>
      println(msg)
      println(e)

  }
}
