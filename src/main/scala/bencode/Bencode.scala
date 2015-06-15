package storrent.bencode

import java.nio.file.{Files, Paths}

object Bencode {

  lazy val decoder = new Decoder
  lazy val encoder = new Encoder

  // Takes in parsed torrent file in iterator[byte] form
  def decode(input: List[Byte]): Map[String, Any] = {
    decoder.decode(input).asInstanceOf[Map[String, Any]]
  }

  def encode(input: Any): Array[Byte] = {
    encoder.encode(input)
  }

  // For test purposes
  def decodeFile(filename: String): Any = {
    val bytes = Files.readAllBytes(Paths.get(filename))
    val input: List[Byte] = bytes.toList
    decode(input)
  }
}

