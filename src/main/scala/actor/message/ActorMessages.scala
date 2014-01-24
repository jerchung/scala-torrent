package org.jerchung.torrent.actor.message

import akka.actor.ActorRef
import akka.util.ByteString
import scala.collection.BitSet
import java.net.InetSocketAddress

// Tracker Client
object TrackerM {
  case class Request(announce: String, request: Map[String, Any])
  case class Response(response: String)
}

// Torrent Client (TorrentM (TorrentMessage))
object TorrentM {
  case class Start(filename: String)
  case class CreatePeer(connection:ActorRef, remote: InetSocketAddress, peerId: Option[ByteString] = None)
  case class DisconnectedPeer(peerId: ByteString, peerHas: BitSet)
  case class Available(update: Either[Int, BitSet])
  case class Unavailable(remove: Either[Int, BitSet])
  case class PieceDone(idx: Int)
  case class PieceInvalid(idx: Int)
  case class Register(peerId: ByteString)
}

// Peer Client
object PeerM {
  case object Connected
  case object Handshake
}

// FileManager
object FM {
  case class Read(index: Int, offset: Int, length: Int)
  case class Write(index: Int, offset: Int, block: ByteString)
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
  case class Bitfield(bitfield: BitSet, numPieces: Int) extends Message
  case class Have(index: Int) extends Message
  case class Request(index: Int, offset: Int, length: Int) extends Message
  case class Piece(index: Int, offset: Int, block: ByteString) extends Message
  case class Cancel(index: Int, offset: Int, length: Int) extends Message
  case class Port(port: Int) extends Message
  case class Handshake(infoHash: ByteString, peerId: ByteString) extends Message
  case class Listener(actor: ActorRef)

  // Messages sent *FROM* TorrentProtocol actor
  sealed trait Reply
  sealed trait UpdateR
  case object KeepAliveR extends Reply
  case object ChokeR extends Reply
  case object UnchokeR extends Reply
  case object InterestedR extends Reply
  case object NotInterestedR extends Reply
  case class BitfieldR(bitfield: BitSet) extends Reply with UpdateR
  case class HaveR(index: Int) extends Reply with UpdateR
  case class RequestR(index: Int, offset: Int, length: Int) extends Reply
  case class PieceR(index: Int, offset: Int, block: ByteString) extends Reply
  case class CancelR(index: Int, offset: Int, length: Int) extends Reply
  case class PortR(port: Int) extends Reply
  case class HandshakeR(infoHash: ByteString, peerId: ByteString) extends Reply
  case object Connected extends Reply
  case object InvalidR extends Reply // Invalid ByteString from peer

}