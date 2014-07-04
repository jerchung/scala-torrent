package org.jerchung.torrent.piece

import akka.util.ByteString
import org.jerchung.torrent.diskIO.DiskIO
import org.jerchung.torrent.Constant
import java.nio.ByteBuffer

sealed trait PieceState
object Disk extends PieceState
object Done extends PieceState
object Fetching extends PieceState
object Unfinished extends PieceState

case class InMemPiece(data: Array[Byte])

case class Piece(
    index: Int,
    offset: Int,
    size: Int,
    hash: Int,
    var state: PieceState = Unfinished) {

  def insert(
      offset: Int,
      block: ByteString):
      (PieceState, Option[ByteString]) = {
    val byteArray = block.toArray
    val numBytes = byteArray.length
    if (offset + numBytes > size) {
      throw new Exception("Out of bounds insertion - block will go outside " +
        "bounds of piece size")
    }
    // Todo -> don't let overlaps result in bytesWritten being updated
    buffer.position(offset)
    buffer.put(byteArray)
    bytesWritten += numBytes

    if (isComplete && hashMatches) {
      write(buffer)
      InMemPiece(index, offset, size, hash, buffer.array)
    } else if (isComplete) {
      clear
      InvalidPiece(index, offset, size, hash)
    } else {
      this
    }
  }
}
