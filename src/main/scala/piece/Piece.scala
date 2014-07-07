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
object Invalid extends PieceState

case class Piece(
  index: Int,
  offset: Int,
  size: Int,
  hash: ByteString
)