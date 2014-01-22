package org.jerchung.torrent.diskIO

abstract class TorrentBytesIO {

  def read(offset: Int): ByteString
  def write(offset: Int, data: ByteString): Int

}