package org.jerchung.torrent

import akk.util.ByteString

// Shared things among actors
object Resource {

  // ByteString prefix of peer messages which stay constant
  lazy val keepAlive = ByteString(0, 0, 0, 0)
  lazy val choke = ByteString(0, 0, 0, 1, 0)
  lazy val unchoke = ByteString(0, 0, 0, 1, 1)
  lazy val interested = ByteString(0, 0, 0, 1, 2)
  lazy val notInterested = ByteString(0, 0, 0, 1, 3)
  lazy val have = ByteString(0, 0, 0, 5, 4)
  lazy val request = ByteString(0, 0, 0, 13, 6)
  lazy val cancel = ByteString(0, 0, 0, 13, 8)
  lazy val port = ByteString(0, 0, 0, 3, 9)
  lazy val handshake = ByteString(19) ++ ByteString.fromString("BitTorrent protocol")

}