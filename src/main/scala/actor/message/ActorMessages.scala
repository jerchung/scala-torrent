package org.jerchung.torrent.actor.message

import akka.actor.ActorRef
import akka.util.ByteString
import scala.collection.BitSet
import java.net.InetSocketAddress

// Tracker Client
object TrackerM {
  case class Request(announce: String, request: Map[String, Any])
}

// Torrent Client (TorrentM (TorrentMessage))
object TorrentM {
  case class Start(filename: String)
  case class CreatePeer(connection: ActorRef, remote: InetSocketAddress, peerId: Option[ByteString] = None)
  case class Available(update: Either[Int, BitSet])
  case class Unavailable(remove: Either[Int, BitSet])
  case class PieceDone(idx: Int)
  case class PieceInvalid(idx: Int)
  case class PieceRequested(idx: Int)
  case class DisconnectedPeer(peerId: ByteString, peerHas: BitSet)
  case class TrackerR(response: String)
}

object PeerM {
  case class Downloaded(id: ByteString, length: Int)
  case class Connected(peerId: ByteString)
  case class Ready(peerHas: BitSet)
  case class Disconnected(id: ByteString, peerHas: BitSet)
  case class ChokedOnPiece(index: Int)
  case class DownloadPiece(index: Int, size: Int)
  case class Resume(index: Int)
  case object ClearPiece
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

  // Messages sent *FROM* TorrentProtocol actor
  sealed trait Reply
  case object KeepAliveR extends Reply
  case object ChokeR extends Reply
  case object UnchokeR extends Reply
  case object InterestedR extends Reply
  case object NotInterestedR extends Reply
  case class BitfieldR(bitfield: BitSet) extends Reply
  case class HaveR(index: Int) extends Reply
  case class RequestR(index: Int, offset: Int, length: Int) extends Reply
  case class PieceR(index: Int, offset: Int, block: ByteString) extends Reply
  case class CancelR(index: Int, offset: Int, length: Int) extends Reply
  case class PortR(port: Int) extends Reply
  case class HandshakeR(infoHash: ByteString, peerId: ByteString) extends Reply
  case object Connected extends Reply
  case object InvalidR extends Reply // Invalid ByteString from peer
}