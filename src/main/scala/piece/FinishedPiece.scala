package org.jerchung.torrent.piece

import akka.util.ByteString

class FinishedPiece(
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