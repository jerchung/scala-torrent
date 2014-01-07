package org.jerchung.torrent

// Namespace ActorMessages (AM)
object AM {

  // Tracker Client
  object Tracker {
    case class Request(announce: String, request: Map[String, Any])
    case class Response(response: String)
  }

  // Torrent Client
  object Torrent {
    case class Start(filename: String)
  }

  // Peer Client
  object Peer {
    case object Connected
    case class Reply(msg: ByteString)
  }

  // Peer Wire TCP Protocol
  object BT {
    sealed case class BTMessage
    case object KeepAlive extends BTMessage
    case object Choke extends BTMessage
    case object Unchoke extends BTMessage
    case object Interested extends BTMessage
    case object NotInterested extends BTMessage
    case object Bitfield extends BTMessage
    case class Have(index: Int) extends BTMessage
    case class Request(index: Int, begin: Int, length: Int) extends BTMessage
    case class Piece(index: Int, begin: Int, block: ByteString) extends BTMessage
    case class Handshake(infoHash: ByteString, peerId: ByteString)
  }

}