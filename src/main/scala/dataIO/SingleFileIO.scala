package org.jerchung.torrent.diskIO

import akka.util.ByteString
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class SingleFileIO(
    name: String,
    pieceSize: Int,
    size: Int)
    extends TorrentBytesIO {

  val NumDiskTries = 3
  val raf: RandomAccessFile = new RandomAccessFile(name, "rw")
  val fc: FileChannel = raf.getChannel

  override def read(index: Int, length: Int): ByteBuffer = {
    val totalOffset = index * pieceSize
    fc.position(totalOffset)
    val bytes = ByteBuffer.allocate(length)

    def readRecur(start: Int, length: Int, tries: Int): Int = {
      if (tries <= 0) {
        throw new Exception("Could not completely read from disk")
      }

      val done = fc.read(bytes, start, length)
      if (done < end)
        readRecur(start + done, length - done, tries - 1)
      else
        done
    }

    readRecur(totalOffset, length, NumDiskTries)
    bytes
  }

  def blockRead(offset: Int, length: Int): ByteBuffer = {
    fc.position(offset)
    val bytes = ByteBuffer.allocate(length)
    val done = fc.read(bytes, 0, length)
    if (done < length) {
      throw new Exception("Disk read underflow reading")
    }
    bytes
  }

  override def write(src: ByteBuffer, index: Int): Int = {
    val totalOffset = index * pieceSize
    val length = src.remaining
    blockWrite(src, totalOffset, length)
  }

  def blockWrite(src: ByteBuffer, offset: Int, length: Int): Int = {
    src.limit(src.limit + length)
    val done = fc.write(src, offset)
    if (done < length) {
      throw new Exception("Disk writing underflow error")
    }
    done
  }

}