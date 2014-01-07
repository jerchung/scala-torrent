package org.jerchung.bencode

import scala.io.Source

object Bencode {

  val decoder = new Decoder

  // Takes in parsed torrent file in iterator[byte] form
  def decode(input: BufferedIterator[Byte]): Map[String, Any] = {
    decoder.decode(input).asInstanceOf[Map[String, Any]]
  }

  // For test purposes
  def decodeFile(filename: String): Any = {
    val source = Source.fromFile(filename, "ISO-8859-1")
    val input = source.map(_.toByte).toIterator.buffered
    decode(input)
  }
}

