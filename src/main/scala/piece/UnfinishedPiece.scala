package org.jerchung.torrent.piece

import akka.util.ByteString

class UnfinishedPiece(
    val index: Int,
    val size: Int,
    val hash: ByteString,
    writer: TorrentBytesIO)
    extends Piece {

  // Important for this to be lazy since then not all pieces get memory
  // allocated to them at the beginning
  private lazy val blocks: ByteBuffer = ByteBuffer.allocate(size)
  private val md = MessageDigest.getInstance("SHA-1")
  private var bytesWritten = 0

  def insert(offset: Int, block: ByteString): Piece = {
    val byteArray = block.toArray
    val numBytes = byteArray.length
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
  def write(data: ByteBuffer): Unit = {
    writer.write(index, offset, data)
  }

  def isComplete: Boolean = {
    bytesWritten == size
  }

  def hashMatches: Boolean = {
    sha1 == hash
  }

  def sha1: Array[Byte] = {
    md.update(buffer)
    md.digest
  }

  def clear: Unit = {
    buffer.clear
    blocks = None
  }
}