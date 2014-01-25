package org.jerchung.torrent.convert

import akka.util.ByteString
import org.jerchung.torrent.Constant
import scala.annotation.tailrec
import scala.collection.BitSet

class ConvertibleBitSet(bits: BitSet) {

  def toByteString(length: Int): ByteString = {
    val builder = ByteString.newBuilder

    @tailrec
    def helper(offset: Int, count: Int): ByteString = {
      if (count >= length) {
        builder.result
      } else {
        val byteChunk = 0 until Constant.ByteSize
        val byte = byteChunk.foldLeft(0) { (v, n) =>
          if (bits contains (n + offset)) {
            v + (1 << (Constant.ByteSize - 1 - n))
          } else {
            v
          }
        }.toByte
        builder += byte
        helper(offset + Constant.ByteSize, count + 1)
      }
    }

    helper(0, 0)
  }

}
