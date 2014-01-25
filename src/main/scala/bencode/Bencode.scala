package org.jerchung.torrent.bencode

import scala.io.Source

object Bencode {

  lazy val decoder = new Decoder
  lazy val encoder = new Encoder

  // Takes in parsed torrent file in iterator[byte] form
  def decode(input: BufferedIterator[Byte]): Map[String, Any] = {
    decoder.decode(input).asInstanceOf[Map[String, Any]]
  }

  def encode(input: Any): String = {
    encoder.encode(input)
  }

  // For test purposes
  def decodeFile(filename: String): Any = {
    val source = Source.fromFile(filename, "ISO-8859-1")
    val input = source.map(_.toByte).toIterator.buffered
    decode(input)
  }
}

