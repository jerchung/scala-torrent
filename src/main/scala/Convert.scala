package org.jerchung.torrent

import akka.util.ByteString
import org.jerchung.torrent.convert._
import scala.language.implicitConversions

object Convert {

  implicit def byteStringConvert(bytes: ByteString): ConvertibleByteString = {
    new ConvertibleByteString(bytes)
  }

}