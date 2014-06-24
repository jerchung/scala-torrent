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
import org.jerchung.torrent.actor.persist.StorageWorker
import org.jerchung.torrent.actor.dependency.BindingKeys._
import org.jerchung.torrent.diskIO._
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

  import FileManager.{ FileWorker => FW }

  // Important values
  val numPieces   = torrent.numPieces
  val pieceSize   = torrent.pieceSize
  val totalSize   = torrent.totalSize
  val piecesHash  = torrent.piecesHash

  // Cache map for quick piece access (pieceIndex -> Piece)
  val cachedPieces = new LruMap[Int, InMemPiece](10)

  // Allows pieces to read/write from disk
  val diskIO: DiskIO = torrent.fileMode match {
    case Single   => new SingleFileIO(torrent.name, pieceSize, totalSize)
    case Multiple => new MultiFileIO(pieceSize, torrent.files)
  }

  val parent = injectOptional [ActorRef](ParentId) getOrElse {
    context.parent
  }

  // Actor that takes care of reading / writing from disk
  val storageWorker = injectOptional [ActorRef](StorageWorkerId) getOrElse {
    torrent.fileMode match {
      case Single =>
        context.actorOf(SingleStorageWorker.props(
          torrent.name,
          pieceSize,
          totalSize
        ))

      case Multi =>
        context.actorOf(MultiStorageWorker.props(torrent.files, pieceSize))
    }
  }

  // Last piece may not be the same size as the others.
  // Create pieces based on index, hash, and size
  val pieces: Vector[Piece] = {
    val pieceHashGrouped = piecesHash.grouped(20).toList

    @tailrec
    def getPieces(
        grouped: List[ByteString],
        pieces: Vector[Piece],
        offset: Int,
        index: Int): Vector[Piece] = {
      if (grouped.isEmpty) {
        pieces
      } else {
        // Different piece constructor args depending if it's last element
        // or not
        val piece = grouped match {
          case hash :: Nil =>
            UnfinishedPiece(idx, idx * pieceSize, totalSize - offset, diskIO)
          case hash :: tail =>
            UnfinishedPiece(idx, idx * pieceSize, pieceSize, hash, diskIO)
        }
        getPieces(grouped.tail, pieces :+ piece, offset + pieceSize, index + 1)
      }
    }

    getPieces(pieceHashGrouped, Vector[Piece](), 0, 0)

  }

  def receive = {
    case Read(idx, off, length) =>
      getBlock(idx, off, length)

    case Write(idx, off, block) =>
      pieces(idx) match {
        case p: UnfinishedPiece => insertBlockAndReport(p, off, block, sender)
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
  def getBlock(index: Int, offset: Int, length: Int): Unit = {
    val byteString: Option[ByteString] =
      if (cachedPieces contains index) {
        val data: Array[Byte] = cachedPieces(index).data
        Some(ByteString.fromArray(data, offset, length))
      } else {
        pieces(index) match {
          case p @ InDiskPiece(idx, off, size, hash, reader) =>
            val data = p.data
            cachedPieces(index) = InMemPiece(idx, off, size, hash, data)
            Some(ByteString.fromArray(data, offset, length))
          case _ => None
        }
      }
    byteString map { b => sender ! BT.Piece(index, offset, b) }
  }

}