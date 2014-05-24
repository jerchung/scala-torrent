package org.jerchung.torrent.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.util.ByteString
import com.twitter.util.LruMap
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.jerchung.torrent.actor.message.BT
import org.jerchung.torrent.actor.message.FM._
import org.jerchung.torrent.actor.message.TorrentM
import org.jerchung.torrent.diskIO._
import org.jerchung.torrent.piece._
import org.jerchung.torrent.Torrent
import org.jerchung.torrent.{ Single, Multiple }
import scala.collection.mutable

object FileManager {
  def props(torrent: Torrent): Props = {
    Props(classOf[FileManager], torrent)
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
class FileManager(torrent: Torrent) extends Actor {

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

  // Last piece may not be the same size as the others.
  // Create pieces based on index, hash, and size
  val pieces: Array[Piece] = {
    var off, idx = 0
    val pieceHashIterator = piecesHash.grouped(20)
    val pieces = new mutable.ArrayBuffer[Piece]
    while (!pieceHashIterator.isEmpty) {
      val hash = pieceHashIterator.next
      val piece =
        if (pieceHashIterator.hasNext)
          new UnfinishedPiece(idx, idx * pieceSize, pieceSize, hash, diskIO)
        else
          new UnfinishedPiece(idx, idx * pieceSize, totalSize - off, hash, diskIO)
      pieces += piece
      off += pieceSize
      idx += 1
    }
    pieces.toArray
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
        peer ! TorrentM.PieceDone(idx)
        pieces(idx) = new InDiskPiece(idx, off, size, hash, diskIO)
        cachedPieces(idx) = p
      case InvalidPiece(idx, off, size, hash) =>
        pieces(idx) = new UnfinishedPiece(idx, off, size, hash, diskIO)
        peer ! TorrentM.PieceInvalid(idx)
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