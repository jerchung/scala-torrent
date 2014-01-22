package org.jerchung.torrent.diskIO

import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class SingleFileIO(name: String, pieceSize: Int) extends TorrentBytesIO {

  val NumDiskTries = 3
  val raf: RandomAccessFile = new RandomAccessFile(name, "rw")
  val fc: FileChannel = raf.getChannel

  override def read(index: Int, offset: Int, length: Int): ByteBuffer = {

    val totalOffset = index * pieceSize + offset
    fc.position(totalOffset)
    val bytes = ByteBuffer.allocate(length)

    def _read(start: Int, length: Int, tries: Int): Int = {
      if (tries <= 0) {
        throw new Exception("Could not completely read from disk")
      }

      val done = fc.read(bytes, start, length)
      if (done < end)
        _read(start + done, length - done, tries - 1)
      else
        done
    }

    _read(totalOffset, length, NumDiskTries)
    bytes
  }

  override def write(src: ByteBuffer, index: Int, offset: Int, length: Int): Int = {
    val totalOffset = index * pieceSize + offset
    fc.position(totalOffset)
    val done = fc.write(src, totalOffset, length)
    if (done < length)
      throw new Exception("Disk writing underflow error")
    else
      done
  }

}