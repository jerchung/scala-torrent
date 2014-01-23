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
    val size: Int)
    extends DiskIO {

  val NumDiskTries = 3
  val raf: RandomAccessFile = new RandomAccessFile(name, "rw")
  val fc: FileChannel = raf.getChannel

  override def read(dest: ByteBuffer, offset: Int): ByteBuffer = {
    val length = dest.remaining
    fc.position(offset)
    fc.read(dest)
    dest
  }

  override def write(src: ByteBuffer, offset: Int): Int = {
    val length = src.remaining
    fc.position(offset)
    fc.write(src)
  }

}