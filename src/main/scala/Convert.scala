package storrent

import akka.util.ByteString
import scala.annotation.tailrec
import scala.collection.BitSet

object Convert {
  implicit class ConvertibleBitSet(bits: BitSet) {

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

  implicit class ConvertibleByteString(bytes: ByteString) {

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
          toIntHelper(bytes.tail, value + bytesVal, idx + 1)
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
      var idx = 0
      for {
        byte <- bytes
        maskedByte = (byte & 0xFF)
        offset <- (Constant.ByteSize - 1 to 0 by -1)
        bit = (maskedByte >> offset) & 0x01
      } yield {
        if (bit == 1) { builder += idx }
        idx += 1
      }

      builder.result
    }
  }

  implicit class ConvertibleString(string: String) {

    def toByteString: ByteString = {
      ByteString.fromString(string)
    }
  }

  implicit class ConvertibleNumber(n: Long) {
    def toByteString(numBytes: Int): ByteString = {

      @tailrec
      def helper(curr: Long, remaining: Int, res: ByteString): ByteString = {
        if (remaining > 0) {
          val byte = curr & 0xFF
          helper(curr >> 4, remaining - 1, byte.toByte +: res)
        } else {
          res
        }
      }

      helper(n, numBytes, ByteString())
    }
  }
}
