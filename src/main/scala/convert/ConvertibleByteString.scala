package org.jerchung.torrent.convert

import org.jerchung.torrent.convert.Convert._

class ConvertibleByteString(bytes: ByteString) {

  // This stuff has to be fast so I'm using bit shifts etc.
  // WARNING - Treats bytes as unsigned ints so no negatives will ever be
  // returned
  def toInt: Int = {
    val size = bytes.length

    def toIntHelper(bytes: ByteString, value: Int = 0, idx: Int = 0): Int = {
      if (bytes.isEmpty) {
        value
      } else {
        val byte = bytes.head
        val bytesVal = (byte & 0xFF) << ((size - 1 - idx) * ByteSize)
        toIntHelper(bytes.drop(1), value + bytesVal, idx + 1)
      }
    }

    toIntHelper(bytes)
  }

  // Gonna have to do some actual bit arithmetic :\
  def toBitSet(bytes: ByteString): BitSet = {
    val builder = BitSet.newBuilder
    var distance = bytes.length * ByteSize - 1
    for {
      byte <- bytes
      maskedByte = (byte & 0xFF)
      offset <- (ByteSize - 1 to 0 by -1)
      bit = (maskedByte >> offset) & 0x01
    } yield {
      if (bit == 1) builder += dist
      dist -= 1
    }
    builder.result
  }
}