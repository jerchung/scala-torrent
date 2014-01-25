package org.jerchung.torrent.bencode

import akka.util.ByteString
import scala.language.implicitConversions

object Conversions {
  implicit def ByteStringToString(bytes: ByteString): String = {
    bytes.decodeString("ISO-8859-1")
  }
}