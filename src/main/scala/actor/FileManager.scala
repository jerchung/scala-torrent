package org.jerchung.torrent.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.util.ByteString
import java.security.MessageDigest
import java.io.RandomAccessFile
import org.jerchung.torrent.Piece
import org.jerchung.torrent.Torrent
import org.jerchung.torrent.{ Single, Multiple }
import scala.collection.mutable
import com.twitter.util.LruMap

object FileManager {
  def props: Props(torrent: Torrent) = {
    Props(classOf[FileManager], torrent)
  }
}

/**
 * Doesn't just manage files, manages pieces, blocks etc. but I couldn't really
 * think of a better name for this actor.
 *
 * Parent of this actor *should* be TorrentClient
 *
 * @torrent Torrent file passed in since many values
 */
class FileManager(torrent: Torrent) extends Actor {

  // Important values
  val numPieces   = torrent.numPieces
  val pieceSize   = torrent.pieceLength
  val totalSize   = torrent.totalSize
  val pieceHashes = torrent.pieceHashes

  // Cache map for quick piece access (pieceIndex -> Piece)
  val cachedPieces = new LruMap[Int, CachedPiece](10)

  // Allows pieces to read/write from disk
  val diskIO: TorrentBytesIO = torrent.fileMode match {
    case Single   => new SingleFileIO
    case Multiple => new MultipleFileIO
  }

  // Last piece may not be the same size as the others.
  val pieces: Array[Piece] = {
    var off, idx = 0
    val pieceHashIterator = pieceHashes.grouped(20)
    val pieces = mutable.ArrayBuffer[Piece]
    while (!pieceHashIterator.isEmpty) {
      val hash = pieceHashIterator.next
      val piece = pieceHashIterator.hasNext match {
        case true => new UnfinishedPiece(idx, pieceSize, hash, diskIO)
        case false => new UnfinishedPiece(idx, totalSize - off, hash, diskIO)
      }
      pieces += piece
      off += pieceSize
      idx += 1
    }
    pieces.toArray
  }

  def receive = {
    case Read(idx, off, length) => getBlock(idx, off, length)
    case Write(idx, off, block) =>
      pieces(index) match {
        case p: UnfinishedPiece => insertBlock(p, idx, off, block)
        case _ =>
      }
  }

  // Will possibly need to add checks for bounded index / offset later
  // This call may have the effect of having disk IO if the piece having
  // the block inserted in ends up being completed. Also takes care of the
  // caching / invalid / finished piece logic
  def insertBlock(piece: UnfinishedPiece, index: Int, offset: Int, block: ByteString): Unit = {
    piece.insert(offset, block) match {
      case p @ CachedPiece(index, size, hash, data) =>
        pieces(index) = new FinishedPiece(index, size, hash, diskIO)
        cachedPieces(index) = p
        parent ! PieceDone(index)
      case InvalidPiece(index, size, hash) =>
        pieces(index) = new UnfinishedPiece(index, size, hash, diskIO)
        parent ! PieceInvalid(index)
      case _ =>
    }
  }

  // Gets the data requested and also has caching logic
  def getBlock(index: Int, offset: Int, length: Int): Unit = {
    val byteString: Option[ByteString] =
      if (cachedPieces contains index) {
        val data: Array[Byte] = cachedPieces(index).data
        Some(ByteString.fromArray(data, offset, length))
      } else {
        pieces(index) match {
          case p: FinishedPiece =>
            val data: Array[Byte] = p.getData.array
            cachedPieces(index) += CachedPiece(index, p.size, p.hash, data)
            Some(ByteString.fromArray(data, offset, length))
          case _ => None
        }
      }
    byteString map { b => sender ! BT.Piece(index, offset, b) }
  }

}