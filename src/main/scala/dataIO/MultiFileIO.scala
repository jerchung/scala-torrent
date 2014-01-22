package org.jerchung.torrent.diskIO

import scala.annotation.tailrec
import scala.collection.mutable

class MultiFileIO(pieceSize: Int, files: List[TorrentFile]) {

  val ioFiles = files map { f => new SingleFileIO(f.path, pieceSize, f.size) }

  case class FileOffset(file: SingleFileIO, offset: Int, length: Int)

  def affectedFiles(index: Int, length: Int): List[SingleFileIO] = {

    @tailrec
    def getAffectedFiles(
        files: List[SingleFileIO],
        start: Int,
        buffer: ListBuffer[SingleFileIO]): List[SingleFileIO] = {
      files match {
        case Nil => buffer.toList
        case file :: moreFiles =>
          val end = start + file.size
          if (end < targetStart)
            getAffectedFiles(moreFiles, end, buffer)
          else if (start >= targetStop)
            buffer.toList
          else
            getAffectedFiles(moreFiles, end,
             buffer += FileOffset(file, ))

      }
    }

    getAffectedFiles(files, 0, mutable.ListBuffer[SingleFileIO]())
  }

  override def read(index: Int, length: Int): ByteBuffer = {

  }
}