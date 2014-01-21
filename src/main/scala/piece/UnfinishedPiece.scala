package org.jerchung.torrent.piece

import akka.util.ByteString

class UnfinishedPiece(
    val index: Int,
    val size: Int,
    val hash: ByteString,
    writer: TorrentBytesIO)
    extends Piece {

  // Important for this to be lazy since then not all pieces get memory
  // allocated to them at the beginning, thus saving memory
  private lazy val blocks: ByteBuffer = ByteBuffer.allocate(size)

  private val md = MessageDigest.getInstance("SHA-1")
  private var bytesWritten = 0

  def insert(offset: Int, block: ByteString): Piece = {
    val byteArray = block.toArray
    val numBytes = byteArray.length
    if (offset + numBytes > size) {
      throw new Exception("Out of bounds insertion - block will go outside " +
        "bounds of piece size")
    }
    buffer.put(block, offset, numBytes)
    bytesWritten += numBytes

    if (isComplete && hashMatches) {
      write(blocks)
      CachedPiece(index, size, hash, blocks.array)
    } else if (isComplete) {
      clear
      InvalidPiece(index, size, hash)
    } else {
      this
    }
  }

  // This should be called only when piece is ready to be written to disk
  private def write(data: ByteBuffer): Unit = {
    writer.write(index, offset, data)
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
    blocks = None
  }
}