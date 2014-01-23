package org.jerchung.torrent

object Constant {
  val ByteSize = 8
  val Charset = "ISO-8859-1"
  val HashAlgo = "SHA-1"

  val ClientID = "ST"
  val Version = "1000"
  val IDSuffix = "576611457638"
  val ID = s"-${ClientID}${Version}-${IDSuffix}"
}