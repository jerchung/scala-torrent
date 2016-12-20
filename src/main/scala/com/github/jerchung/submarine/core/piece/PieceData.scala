package com.github.jerchung.submarine.core.piece

import akka.util.ByteString

sealed trait PieceData {
  def index: Int

  def update(offset: Int, block: ByteString): PieceData = {
    this
  }
}

object PieceData {
  case class Incomplete(index: Int, pieceSize: Int, pieceHash: IndexedSeq[Byte]) extends PieceData {
    lazy val data: Array[Byte] = new Array(pieceSize)
    private var bytesInserted = 0

    override def update(offset: Int, block: ByteString): PieceData = {
      block.view.zipWithIndex.foreach {
        case (byte, i) =>
          data(offset + i) = byte
      }

      bytesInserted += block.size

      if (bytesInserted < pieceSize) {
        this
      } else if (bytesInserted == pieceSize && Pieces.hashMatches(pieceHash, data)) {
        Complete(index, data)
      } else {
        Invalid(index)
      }
    }
  }

  case class Complete(index: Int, piece: Array[Byte]) extends PieceData

  case class Invalid(index: Int) extends PieceData
}
