package org.jerchung.torrent.piece

import akka.util.ByteString
import java.security.MessageDigest
import java.nio.ByteBuffer

class UnfinishedPiece(
    val index: Int,
    val offset: Int,
    val size: Int,
    val hash: ByteString,
    writer: TorrentBytesIO)
    extends Piece {

  // Important for this to be lazy so that not all pieces get memory
  // allocated to them at the beginning upon initialization, thus saving memory
  private lazy val buffer: ByteBuffer = ByteBuffer.allocate(size)

  private val md = MessageDigest.getInstance("SHA-1")
  private var bytesWritten = 0

  def insert(offset: Int, block: ByteString): Piece = {
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

  // This should be called only when piece is ready to be written to disk
  private def write(data: ByteBuffer): Unit = {
    writer.write(data, offset)
  }

  private def isComplete: Boolean = {
    bytesWritten == size
  }

  private def hashMatches: Boolean = {
    sha1.sameElements(hash)
  }

  private def sha1: Array[Byte] = {
    md.update(buffer)
    md.digest
  }

  private def clear: Unit = {
    buffer.clear
  }
}