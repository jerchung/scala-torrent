package org.jerchung.torrent.piece

import akka.util.ByteString
import org.jerchung.torrent.diskIO.DiskIO
import org.jerchung.torrent.Constant
import java.nio.ByteBuffer

trait Piece {
  def index: Int
  def offset: Int
  def size: Int
  def hash: ByteString
}

case class InDiskPiece(
    index: Int,
    offset: Int,
    size: Int,
    hash: ByteString,
    reader: DiskIO)
    extends Piece {

  // This call goes to disk and retrieves the data associated with this piece
  def data: Array[Byte] = {
    val bytes = ByteBuffer.allocate(size)
    reader.read(bytes, offset)
    bytes.array
  }
}

case class InvalidPiece(index: Int, offset: Int, size: Int, hash: ByteString) extends Piece

case class InMemPiece(index: Int, offset: Int, size: Int, hash: ByteString, data: Array[Byte]) extends Piece