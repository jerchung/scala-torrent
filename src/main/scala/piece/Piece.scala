package org.jerchung.torrent.piece

import akka.ByteString.ByteString

trait Piece {
  def index: Int
  def size: Int
  def hash: ByteString
}

class InDiskPiece(
    val index: Int,
    val size: Int,
    val hash: ByteString,
    reader: TorrentBytesIO)
    extends Piece {

  // This call goes to disk and retrieves the data associated with this piece
  def data: ByteBuffer = {
    reader.read(index, size)
  }
}

case class InvalidPiece(index: Int, size: Int, hash: ByteString) extends Piece

case class InMemPiece(index: Int, size: Int, hash: ByteString, data: Array[Byte]) extends Piece