package org.jerchung.torrent.piece

import akka.util.ByteString
import org.jerchung.torrent.diskIO.DiskIO
import org.jerchung.torrent.Constant
import java.nio.ByteBuffer

sealed trait PieceState
object InDisk extends PieceState
object Fetching extends PieceState
object Unfinished extends PieceState

case class Piece(
  index: Int,
  offset: Int,
  size: Int,
  hash: Int,
  state: PieceState = Unfinished,
  data: Option[Array[Byte]] = None
)

case class InMemPiece(data: Array[Byte])
