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
  object PeerM {
    case object Connected
    case class Reply(msg: ByteString)
  }

  // Peer Wire TCP Protocol
  object BT {

    sealed trait Message
    case object KeepAlive extends Message
    case object Choke extends Message
    case object Unchoke extends Message
    case object Interested extends Message
    case object NotInterested extends Message
    case object Bitfield extends Message
    case class Have(index: Int) extends Message
    case class Request(index: Int, begin: Int, length: Int) extends Message
    case class Piece(index: Int, begin: Int, block: ByteString) extends Message
    case class Handshake(infoHash: ByteString, peerId: ByteString) extends Message

    case object Connected

    object Reply {

      sealed trait Reply
      case object KeepAlive extends Reply
      case object Choke extends Reply
      case object Unchoke extends Reply
      case object Interested extends Reply
      case object NotInterested extends Reply
      case object Bitfield extends Reply
      case class Have(index: Int) extends Reply
      case class Request(index: Int, begin: Int, length: Int) extends Reply
      case class Piece(index: Int, begin: Int, block: ByteString) extends Reply
      case class Handshake(infoHash: ByteString, peerId: ByteString) extends Reply
    }
  }

}