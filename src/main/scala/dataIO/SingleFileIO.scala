package org.jerchung.torrent.diskIO

import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class SingleFileIO(name: String, pieceSize: Int) extends TorrentBytesIO {

  val raf: RandomAccessFile = new RandomAccessFile(name, "rw")
  val fc: FileChannel = raf.getChannel

  def read(index: Int, offset: Int, length: Int): ByteBuffer = {
    val totalOffset = index * pieceSize + offset

  }

}