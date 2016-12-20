package com.github.jerchung.submarine.core.setting

import scala.concurrent.duration._
import scala.language.postfixOps

object Constant {
  val ByteSize = 8
  val Charset = "ISO-8859-1"
  val HashAlgo = "SHA-1"

  // Block size is 2^14 or 16kB
  val BlockSize = 16384

  val ClientID = "ST"
  val Version = "1000"
  val IDSuffix = "576611457638"
  val ID = s"-${ClientID}${Version}-${IDSuffix}"
  val NumUnchokedPeers = 4
  val UnchokeFrequency = 10 seconds
}
