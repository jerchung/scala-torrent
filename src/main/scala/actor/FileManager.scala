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
 * think of a better name for this actor
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
  val diskIO = torrent.fileMode match {
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

  // Hashes of each piece for later comparison
  val pieceHashes: Array[Array[Byte]] = pieces.grouped(20)

  // buffer to hold pieces that are being downloaded piece by piece
  var inProgress = Map[Int, Map[Int, ByteString]]().withDefaultValue(Map.empty)

  def receive = {
    case Write(index, offset, block) => insertBlock(index, offset, block)
    case Read(index, offset, length) => readBlock(index, offset, length)
  }

  // Will possibly need to add checks for bounded index / offset later
  // This call may have the side effect of having disk IO if the piece having
  // the block inserted in ends up being completed
  def insertBlock(index: Int, offset: Int, block: ByteString): Unit = {
    pieces(index).insert(offset, block) match {
      case p @ CachedPiece(index, size, hash, data) =>
        pieces(index) = new FinishedPiece(index, size, hash)
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
    if (cachedPieces contains index) {
      val piece = cachedPieces(index)
      val byteString = ByteString.fromArray(piece.data, offset, length)
      sender ! BT.Piece(index, offset, byteString)
    } else {
      pieces(index) match {
        case p: FinishedPiece =>
          val data = p.getData.array
          val byteString = ByteString.fromArray(data, offset, length)
          cachedPieces(index) += CachedPiece(index, p.size, p.hash, data)
          sender ! BT.Piece(index, offset, byteString)
        case _ =>
      }
    }
  }

  def sha1(bytes: ByteString): Array[Byte] = {
    MessageDigest.getInstance("SHA-1").digest(bytes)
  }

  // Check if piece is done and flush it to disk
  def checkComplete(buffer: Map[Int, ByteString]) = {
    if (buffer.length == numBlocks) {

    }
  }

}