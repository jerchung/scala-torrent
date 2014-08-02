package org.jerchung.torrent.convert

import akka.util.ByteString
import akka.util.ByteStringBuilder
import org.jerchung.torrent.Constant
import scala.annotation.tailrec
import scala.collection.BitSet

class ConvertibleBitSet(bits: BitSet) {

  def toByteString(length: Int): ByteString = {

    @tailrec
    def helper(
        offset: Int,
        count: Int,
        builder: ByteStringBuilder): ByteString = {
      if (count >= length) {
        builder.result
      } else {
        val byteChunk = 0 until Constant.ByteSize
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
