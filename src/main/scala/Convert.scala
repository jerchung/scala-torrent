package org.jerchung.torrent

import akka.util.ByteString
import org.jerchung.torrent.convert._

object Convert {

  implicit def byteStringConvert(bytes: ByteString): ConvertibleByteString = {
    new ConvertibleByteString(bytes)
  }

}