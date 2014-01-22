package org.jerchung.torrent.diskIO

import scala.annotation.tailrec
import scala.collection.mutable

class MultiFileIO(pieceSize: Int, files: List[TorrentFile]) {

  val ioFiles = files map { f => new SingleFileIO(f.path, pieceSize, f.size) }

  case class FileOffset(file: SingleFileIO, offset: Int, length: Int)

  def affectedFiles(index: Int, length: Int): List[SingleFileIO] = {
    val targetStart = index * pieceSize
    val targetStop = targetStart + length

    @tailrec
    def getAffectedFiles(
        files: List[SingleFileIO],
        pos: Int,
        buffer: ListBuffer[FileOffset]): List[FileOffset] = {
      files match {
        case Nil => buffer.toList
        case f :: more =>
          val end = pos + f.size
          if (end < targetStart) {
            getAffectedFiles(more, end, buffer)
          } else if (pos >= targetStop) {
            buffer.toList
          } else {
            val offset = if (targetStart - pos > 0) pos else 0
            val readLength = math.min(f.size - offset, targetStop - pos)
            val fileOffset = FileOffset(f, offset, readLength)
            getAffectedFiles(more, end, buffer += fileOffset)
          }
      }
    }

    getAffectedFiles(ioFiles, 0, mutable.ListBuffer[SingleFileIO]())
  }

  override def read(index: Int, length: Int): ByteBuffer = {
    val relevantFiles = affectedFiles(index, length)
    val bytes = ByteBuffer.allocate(length)
    relevantFiles foreach { f =>
      bytes.put(f.file.blockRead(f.offset, f.length))
    }
    bytes
  }

  override def write(src: ByteBuffer, index: Int): Int {
    val length = src.remaining
    val relevantFiles = affectedFiles(index, length)
    relevantFiles foreach { f => f.blockWrite(src, f.offset, f.length) }
    length
  }
}