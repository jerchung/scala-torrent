package org.jerchung.torrent

import akka.util.ByteString
import scala.collection.BitSet
import org.jerchung.torrent.convert._
import scala.language.implicitConversions

object Convert {

  implicit def byteStringConvert(bytes: ByteString): ConvertibleByteString = {
    new ConvertibleByteString(bytes)
  }

  implicit def bitSetConvert(bits: BitSet): ConvertibleBitSet = {
    new ConvertibleBitSet(bits)
  }

  implicit def stringConvert(string: String): ConvertibleString = {
    new ConvertibleString(string)
  }

}