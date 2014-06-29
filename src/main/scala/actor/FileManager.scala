package org.jerchung.torrent.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.util.ByteString
import com.twitter.util.LruMap
import java.io.RandomAccessFile
import java.security.MessageDigest
import com.escalatesoft.subcut.inject._
import org.jerchung.torrent.actor.message.BT
import org.jerchung.torrent.actor.message.FM._
import org.jerchung.torrent.actor.message.PeerM
import org.jerchung.torrent.actor.persist.MultiFileWorker
import org.jerchung.torrent.actor.persist.SingleFileWorker
import org.jerchung.torrent.actor.dependency.BindingKeys._
import org.jerchung.torrent.piece._
import org.jerchung.torrent.Torrent
import org.jerchung.torrent.{ Single, Multiple }
import scala.collection.mutable

object FileManager {
  def props(torrent: Torrent): Props = {
    Props(new FileManager(torrent))
  }

  // Messages to be used internally between FileManager and Storage worker
  object FileWorker {
    case class Read(offset: Int, length: Int)
    case class Write(offset: Int, block: ByteString)
  }

  // Used to store info about requests that came in from various peers
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

  // Important values
  val numPieces   = torrent.numPieces
  val pieceSize   = torrent.pieceSize
  val totalSize   = torrent.totalSize
  val piecesHash  = torrent.piecesHash

  // Cache map for quick piece access (pieceIndex -> Piece)
  val cachedPieces = new LruMap[Int, InMemPiece](10)

  // pieceIndex -> BlockRequest
  var queuedRequests = Map[Int, BlockRequest]()

  val parent = injectOptional [ActorRef](ParentId) getOrElse {
    context.parent
  }

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

  // Last piece may not be the same size as the others.
  // Create pieces based on index, hash, and size
  val pieces: Array[Piece] = {
    val piecesHashGrouped = piecesHash.grouped(20).toList

    piecesHashGrouped.foldLeft((mutable.ArrayBuffer[Piece](), off, idx)) {
      case ((pieces, offset, idx), hash) =>
        val size = pieceSize min totalSize - offset
        val piece = Piece(idx, idx * pieceSize, size, hash)
        (pieces += piece, offset + size, idx + 1)
    }._1.toArray
  }

  def receive = {
    case Read(idx, off, length) =>
      readBlock(idx, off, length)

    case FW.ReadDone(idx, block) =>
      pieces(idx).state = InDisk
      cachedPieces += (idx -> InMemPiece(block))
      fulfillRequests(idx, block)

    case Write(idx, off, block) =>
      pieces(idx).state match {
        case Unfinished => insertBlockAndReport(p, off, block, sender)
        case _ =>
      }
  }

  // Will possibly need to add checks for bounded index / offset later
  // This call may have the effect of having disk IO if the piece having
  // the block inserted in ends up being completed. Also takes care of the
  // caching / invalid / finished piece logic
  def insertBlockAndReport(
      piece: UnfinishedPiece,
      offset: Int,
      block: ByteString,
      peer: ActorRef): Unit = {
    piece.insert(offset, block) match {
      case p @ InMemPiece(idx, off, size, hash, data) =>
        peer ! PeerM.PieceDone(idx)
        pieces(idx) = new InDiskPiece(idx, off, size, hash, diskIO)
        cachedPieces(idx) = p
      case InvalidPiece(idx, off, size, hash) =>
        pieces(idx) = new UnfinishedPiece(idx, off, size, hash, diskIO)
        peer ! PeerM.PieceInvalid(idx)
      case _ =>
    }
  }

  // Gets the data requested and also has caching logic
  // Basically get a block from within a piece at index with offset and length
  def readBlock(index: Int, offset: Int, length: Int): Unit = {
    if (cachedPieces contains index) {
      val data: Array[Byte] = cachedPieces(index).data
      val block = ByteString.fromArray(data, offset, length)
      sender ! BT.Piece(index, offset, block)
    } else {
      val Piece(idx, off, size, hash, state) = pieces(index)
      state match {
        case InDisk =>
          fileWorker ! FW.Read(idx, off, size)
          queueRequest(sender, idx, off, length)
          pieces(index).state = Fetching
        case Fetching(idx, off, size, hash) =>
          queueRequest(sender, idx, off, length)
        case _ => // Do nothing (should not get here)
      }
    }
  }

  // Update queued requests by adding
  def queueRequest(peer: ActorRef, index: Int, offset: Int, length: Int): Unit = {
    val request = BlockRequest(peer, offset, length)
    val existingReqs = queuedRequests.getOrElse(index, Vector[BlockRequest]())
    queuedRequests += (index -> existingReqs :+ request)
  }

  // Get rid of all the queued requests for that particular piece
  def fulfillRequests(index: Int, block: Array[Byte]): Unit = {
    queuedRequests.getOrElse(index, Vector[BlockRequest]()) foreach {
      case BlockRequest(peer, offset, length) =>
        val bytes = ByteString.fromArray(block, offset, length)
        peer ! BT.Piece(index, offset, bytes)
    }
    queuedRequests -= index
  }

}