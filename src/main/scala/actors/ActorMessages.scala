package org.jerchung.torrent

import akka.actor.ActorRef
import akka.util.ByteString

// Namespace ActorMessage
object ActorMessage {

  // Tracker Client
  object TrackerM {
    case class Request(announce: String, request: Map[String, Any])
    case class Response(response: String)
  }

  // Torrent Client (TorrentM (TorrentMessage))
  object TorrentM {
    case class Start(filename: String)
    case class GetPeer(infoHash: ByteString, peerId: ByteString, connection: ActorRef)
  }

  // Peer Client
  object PeerM {
    case object Connected
    case object Handshake
  }

  // Peer Wire TCP Protocol
  object BT {

    // Messages sent *TO* TorrentProtocol actor
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
    case class Cancel(index: Int, begin: Int, length: Int) extends Message
    case class Port(port: Int) extends Message
    case class Handshake(infoHash: ByteString, peerId: ByteString) extends Message
    case class Listener(actor: ActorRef)

    // Messages sent *FROM* TorrentProtocol actor
    sealed trait Reply
    sealed trait Update
    case object KeepAliveR extends Reply
    case object ChokeR extends Reply
    case object UnchokeR extends Reply
    case object InterestedR extends Reply
    case object NotInterestedR extends Reply
    case class BitfieldR(bitfield: Long) extends Reply with Update
    case class HaveR(index: Int) extends Reply with Update
    case class RequestR(index: Int, begin: Int, length: Int) extends Reply
    case class PieceR(index: Int, begin: Int, block: ByteString) extends Reply
    case class CancelR(index: Int, begin: Int, length: Int) extends Reply
    case class PortR(port: Int) extends Reply
    case class HandshakeR(infoHash: ByteString, peerId: ByteString) extends Reply
    case object Connected extends Reply
    case object InvalidR extends Reply // Invalid ByteString from peer

  }

}