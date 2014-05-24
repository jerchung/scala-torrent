package org.jerchung.torrent.diskIO

import scala.annotation.tailrec
import scala.collection.mutable
import java.nio.ByteBuffer
import org.jerchung.torrent.TorrentFile

class MultiFileIO(pieceSize: Int, files: List[TorrentFile]) extends DiskIO {

  val ioFiles = files map { f => new SingleFileIO(f.path, pieceSize, f.length) }
  val totalSize = files.foldLeft(0) { (acc, f) => acc + f.length }

  case class FileOffset(file: SingleFileIO, offset: Int, length: Int)

  def affectedFiles(offset: Int, length: Int): List[FileOffset] = {
    val targetStart = offset
    val targetStop = targetStart + length

    @tailrec
    def getAffectedFiles(
        files: List[SingleFileIO],
        pos: Int,
        affected: List[FileOffset]): List[FileOffset] = {
      files match {
        case Nil => affected.reverse
        case f :: more =>
          val end = pos + f.size
          if (end < targetStart) {
            getAffectedFiles(more, end, affected)
          } else if (pos >= targetStop) {
            affected.reverse
          } else {
            val offset = if (targetStart > pos) pos else 0
            val readLength = math.min(f.size - offset, targetStop - pos)
            val fileOffset = FileOffset(f, offset, readLength)
            getAffectedFiles(more, end, fileOffset :: affected)
          }
      }
    }

    getAffectedFiles(ioFiles, 0, List[FileOffset]())
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