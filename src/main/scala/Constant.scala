package org.jerchung.torrent

import scala.concurrent.duration._
import scala.language.postfixOps

object Constant {
  final val ByteSize = 8
  final val Charset = "ISO-8859-1"
  final val HashAlgo = "SHA-1"

  // Block size is 2^14 or 16kB
  final val BlockSize = 16384

  private val ClientID = "ST"
  private val Version = "1000"
  private val IDSuffix = "576611457638"
  final val ID = s"-${ClientID}${Version}-${IDSuffix}"
  val NumUnchokedPeers = 4
  val UnchokeFrequency = 10 seconds
}