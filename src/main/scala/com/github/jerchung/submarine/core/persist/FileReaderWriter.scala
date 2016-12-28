package com.github.jerchung.submarine.core.persist

import java.io.{File, IOException, RandomAccessFile}

import com.github.jerchung.submarine.core.base.Torrent

object FileReaderWriter {
  trait Provider {
    def singleFile(path: String, size: Int): SingleFile
    def multiFile(torrentFiles: List[Torrent.File], rootFolder: String, size: Int): MultiFile
  }

  trait AppProvider extends Provider {
    override def singleFile(path: String,
                            size: Int): SingleFile =
      new SingleFile(path, size)

    override def multiFile(torrentFiles: List[Torrent.File],
                           rootFolder: String,
                           size: Int): MultiFile =
      new MultiFile(torrentFiles, rootFolder, size)
  }
}

trait FileReaderWriter {
  def readBytes(offset: Int, length: Int, buffer: Array[Byte]): Int
  def writeBytes(offset: Int, data: Array[Byte]): Int
  def size: Int
}

class SingleFile(val path: String, val size: Int) extends FileReaderWriter {
  val raf: RandomAccessFile = new RandomAccessFile(path, "rw")

  override def readBytes(fileOffset: Int, length: Int, buffer: Array[Byte]): Int = {
    readBytes(fileOffset, length, buffer, 0)
  }

  def readBytes(fileOffset: Int, length: Int, buffer: Array[Byte], bufferOffset: Int): Int = {
    if (fileOffset + length > raf.length()) {
      0
    } else {
      try {
        raf.seek(fileOffset)
        raf.read(buffer, bufferOffset, length)
      } catch {
        case _: IOException => 0
      }
    }
  }

  override def writeBytes(fileOffset: Int, data: Array[Byte]): Int = {
    writeBytes(fileOffset, data, 0, data.length)
  }

  def writeBytes(fileOffset: Int, data: Array[Byte], dataOffset: Int, length: Int): Int = {
    try {
      raf.seek(fileOffset)
      raf.write(data, dataOffset, length)
      length
    } catch {
      case _: IOException => 0
    }
  }
}

class MultiFile(torrentFiles: List[Torrent.File], rootFolder: String, val size: Int) extends FileReaderWriter {
  val files: List[SingleFile] = torrentFiles.map { f =>
    new SingleFile(rootFolder + File.separator + f.path, f.size)
  }

  override def readBytes(offset: Int, length: Int, buffer: Array[Byte]): Int = {
    val affected = affectedFiles(offset, length)

    affected.foldLeft((0, 0)) {
      case ((bufferOffset, bytesRead), (f, fileOffset, fileLength)) =>
        val read = f.readBytes(fileOffset, fileLength, buffer, bufferOffset)
        (bufferOffset + fileLength, bytesRead + read)
    }._2
  }

  override def writeBytes(offset: Int, data: Array[Byte]): Int = {
    val affected = affectedFiles(offset, data.length)

    affected.foldLeft((0, 0)) {
      case ((dataOffset, bytesWritten), (f, fileOffset, fileLength)) =>
        val written = f.writeBytes(fileOffset, data, dataOffset, fileLength)
        (dataOffset + fileLength, bytesWritten + written)
    }._2
  }

  private def affectedFiles(offset: Int, length: Int): List[(SingleFile, Int, Int)] = {
    val start = offset
    val stop = start + length

    files.foldLeft((List[(SingleFile, Int, Int)](), 0)) {
      case ((result, position), f) =>
        val currentEnd = position + f.size

        if (currentEnd < start || position > stop) {
          (result, currentEnd)
        } else {
          val fileOffset = if (start > position) start - position else 0
          val fileLength = (f.size - fileOffset).min(stop - position)

          ((f, fileOffset, fileLength) :: result, currentEnd)
        }
    }._1
  }
}
