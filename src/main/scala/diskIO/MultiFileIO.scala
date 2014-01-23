package org.jerchung.torrent.diskIO

import scala.annotation.tailrec
import scala.collection.mutable
import java.nio.ByteBuffer
import org.jerchung.torrent.TorrentFile

class MultiFileIO(pieceSize: Int, files: List[TorrentFile]) extends DiskIO{

  val ioFiles = files map { f => new SingleFileIO(f.path, pieceSize, f.length) }
  val size = files.foldLeft(0) { (acc, f) => acc + f.length }

  case class FileOffset(file: SingleFileIO, offset: Int, length: Int)

  def affectedFiles(offset: Int, length: Int): List[FileOffset] = {
    val targetStart = offset
    val targetStop = targetStart + length

    @tailrec
    def getAffectedFiles(
        files: List[SingleFileIO],
        pos: Int,
        buffer: mutable.ListBuffer[FileOffset]): List[FileOffset] = {
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

    getAffectedFiles(ioFiles, 0, new mutable.ListBuffer[FileOffset])
  }

  override def read(dest: ByteBuffer, offset: Int): ByteBuffer = {
    val length = dest.remaining
    val relevantFiles = affectedFiles(offset, length)
    relevantFiles foreach { f =>
      val fileIO = f.file
      dest.limit(dest.position + f.length)
      fileIO.read(dest, f.offset)
    }
    dest
  }

  override def write(src: ByteBuffer, offset: Int): Int = {
    val length = src.remaining
    val relevantFiles = affectedFiles(offset, length)
    relevantFiles foreach { f =>
      val fileIO = f.file
      src.limit(src.position + f.length)
      fileIO.read(src, f.offset)
    }
    length
  }
}