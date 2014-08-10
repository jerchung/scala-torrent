package org.jerchung.torrent.convert

import akka.util.ByteString
import akka.util.ByteStringBuilder
import org.jerchung.torrent.Constant
import scala.annotation.tailrec
import scala.collection.BitSet

class ConvertibleBitSet(bits: BitSet) {

  /*
   * Takes 8 bit chunks of the BitSet, converts each 8 bit chunk into an
   * unsigned integer byte, and then adds that byte as an entry in the
   * ByteString
   */
  def toByteString(length: Int): ByteString = {

    val byteChunk = 0 until Constant.ByteSize

    @tailrec
    def helper(
        offset: Int,
        count: Int,
        builder: ByteStringBuilder): ByteString = {
      if (count >= length) {
        builder.result
      } else {
        val byte = byteChunk.foldLeft(0) { (v, n) =>
          if (bits contains (n + offset))
            v + (1 << (Constant.ByteSize - 1 - n))
          else
            v
        }.toByte
        helper(offset + Constant.ByteSize, count + 1, builder += byte)
      }
    }

    helper(0, 0, ByteString.newBuilder)
  }

}
