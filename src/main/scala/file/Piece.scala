package storrent.file

import akka.util.ByteString

case class Piece(
  index: Int,
  offset: Int,
  size: Int,
  hash: ByteString
)

sealed trait PieceState
case object Disk extends PieceState
case object Fetching extends PieceState
case object Unfinished extends PieceState
case object Invalid extends PieceState
case object Done extends PieceState
