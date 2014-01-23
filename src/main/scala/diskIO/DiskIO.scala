package org.jerchung.torrent.diskIO

import java.nio.ByteBuffer

abstract class DiskIO {

  def read(dest: ByteBuffer, offset: Int): ByteBuffer
  def write(src: ByteBuffer, offset: Int): Int

}