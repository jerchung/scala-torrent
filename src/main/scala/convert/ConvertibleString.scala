package org.jerchung.torrent.convert

import akka.util.ByteString

class ConvertibleString(string: String) {

  def toByteString: ByteString = {
    ByteString.fromString(string)
  }

}