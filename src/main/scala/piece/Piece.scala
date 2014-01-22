package org.jerchung.torrent.piece

import akka.ByteString.ByteString

trait Piece {
  def index: Int
  def size: Int
  def hash: ByteString
}

case class InvalidPiece(index: Int, size: Int, hash: ByteString) extends Piece

case class InMemPiece(index: Int, size: Int, hash: ByteString, data: Array[Byte]) extends Piece