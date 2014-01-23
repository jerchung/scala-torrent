package org.jerchung.torrent.convert

import akka.util.ByteString
import org.jerchung.torrent.Constant
import scala.annotation.tailrec
import scala.collection.BitSet

class ConvertibleByteString(bytes: ByteString) {

  // This stuff has to be fast so I'm using bit shifts etc.
  // WARNING - Treats bytes as unsigned ints so no negatives will ever be
  // returned
  def toInt: Int = {
    val size = bytes.length

    @tailrec
    def toIntHelper(bytes: ByteString, value: Int = 0, idx: Int = 0): Int = {
      if (bytes.isEmpty) {
        value
      } else {
        val byte = bytes.head
        val bytesVal = (byte & 0xFF) << ((size - 1 - idx) * Constant.ByteSize)
        toIntHelper(bytes.drop(1), value + bytesVal, idx + 1)
      }
    }

    toIntHelper(bytes)
  }

  def toChars: String = {
    bytes.decodeString(Constant.Charset)
  }

  // Gonna have to do some actual bit arithmetic :\
  def toBitSet: BitSet = {
    val builder = BitSet.newBuilder
    var distance = bytes.length * Constant.ByteSize - 1
    for {
      byte <- bytes
      maskedByte = (byte & 0xFF)
      offset <- (Constant.ByteSize - 1 to 0 by -1)
      bit = (maskedByte >> offset) & 0x01
    } yield {
      if (bit == 1) builder += distance
      distance -= 1
    }
    builder.result
  }
}