package org.jerchung.torrent.convert

object Convert {

  val ByteSize = 8

  def byteStringConvert(bytes: ByteString): ConvertibleByteString = {
    new ConvertibleByteString(bytes)
  }
}