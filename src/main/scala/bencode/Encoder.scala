package org.jerchung.torrent.bencode

import akka.util.ByteString
import org.jerchung.torrent.Convert._

class Encoder {

  def encode(message: Any): String = {
    message match {
      case i: Int => encodeInt(i)
      case s: ByteString => encodeString(s)
      case m: Map[_,_] => encodeMap(m.asInstanceOf[Map[String, Any]])
      case l: List[_] => encodeList(l)
      case _ => throw new Exception()
    }
  }

  def encodeInt(value: Int): String = s"i${value}e"

  def encodeString(value: ByteString): String =
    s"""${value.length}:${value.toChars}"""

  // Map keys must be in alphabetical order
  def encodeMap(value: Map[String, Any]): String =
    "d" + value.toList.sortWith((a, b) => a._1 < b._1)
      .foldLeft("") { case (enc, (k, v)) => enc + s"${k.length}:$k${encode(v)}" } + "e"

  def encodeList(value: List[Any]): String =
    "l" + value.foldLeft("") { (enc, v) => enc + encode(v) } + "e"
}