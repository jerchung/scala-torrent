package org.jerchung.torrent.actor.message

import akka.actor.ActorRef
import akka.util.ByteString
import scala.annotation.tailrec
import scala.collection.BitSet
import java.net.InetSocketAddress
import org.jerchung.torrent.Constant
import org.jerchung.torrent.Convert._

// Tracker Client
object TrackerM {
  case class Request(announce: String, request: Map[String, Any])
}

// Torrent Client (TorrentM (TorrentMessage))
object TorrentM {
  case class Start(filename: String)
  case class CreatePeer(connection: ActorRef, remote: InetSocketAddress, peerId: Option[ByteString] = None)
  case class TrackerR(response: String)
}

object PeerM {
  case class Downloaded(length: Int)
  case object Connected
  case class ReadyForPiece(peerHas: BitSet)
  case class Disconnected(peerHas: BitSet)
  case class ChokedOnPiece(index: Int)
  case class DownloadPiece(index: Int, size: Int)
  case class Resume(index: Int)
  case class PieceAvailable(update: Either[Int, BitSet])
  case class PieceDone(idx: Int)
  case class PieceInvalid(idx: Int)
  case class Interested(id: ByteString)
  case object ClearPiece
}

// FileManager
object FM {
  case class Read(index: Int, offset: Int, length: Int)
  case class Write(index: Int, offset: Int, block: ByteString)
}

// FileWorker
object FW {
  case class Read(index: Int, offset: Int, length: Int)
  case class Write(index: Int, offset: Int, block: Array[Byte])
  case class ReadDone(index: Int, block: Array[Byte])
  case class WriteDone(index: Int)
}

// Peer Wire TCP Protocol
object BT {

  val protocol = ByteString.fromString("BitTorrent protocol")

  // Take in an int and the # of bytes it should be represented by, return the
  // corresponding ByteString of the int with appropriate leading 0s
  // Works for multiple nums of the same size
  // Don't use ByteBuffer since I need speed.
  private def byteStringify(size: Int, nums: Int*): ByteString = {
    val builder = ByteString.newBuilder
    for (
      n <- nums;
      idx <- 0 until size
    ) {
      val shift = Constant.ByteSize * (size - 1 - idx)
      builder += ((n >> shift) & 0xFF).toByte
    }
    builder.result
  }

  // Messages sent *TO* TorrentProtocol actor
  sealed trait Message {
    def toByteString: ByteString
  }

  case object KeepAlive extends Message {
    lazy val toByteString = ByteString(0, 0, 0, 0)
  }

  case object Choke extends Message {
    lazy val toByteString = ByteString(0, 0, 0, 1, 0)
  }

  case object Unchoke extends Message {
    lazy val toByteString = ByteString(0, 0, 0, 1, 1)
  }

  case object Interested extends Message {
    lazy val toByteString = ByteString(0, 0, 0, 1, 2)
  }

  case object NotInterested extends Message {
    lazy val toByteString = ByteString(0, 0, 0, 1, 3)
  }

  case class Bitfield(bitfield: BitSet, numPieces: Int) extends Message {
    lazy val toByteString = {
      val numBytes = math.ceil(numPieces.toFloat / Constant.ByteSize).toInt
      byteStringify(4, 1 + numBytes) ++ ByteString(5) ++
        bitfield.toByteString(numBytes)
    }
  }

  case class Have(index: Int) extends Message {
    lazy val toByteString = ByteString(0, 0, 0, 5, 4) ++ byteStringify(4, index)
  }

  case class Request(index: Int, offset: Int, length: Int) extends Message {
    lazy val toByteString =
      ByteString(0, 0, 0, 13, 6) ++ byteStringify(4, index, offset, length)
  }

  case class Piece(index: Int, offset: Int, block: ByteString) extends Message {
    lazy val toByteString = byteStringify(4, 9 + block.length) ++
      ByteString(7) ++ byteStringify(4, index, offset) ++ block
  }

  case class Cancel(index: Int, offset: Int, length: Int) extends Message {
    lazy val toByteString =
      ByteString(0, 0, 0, 13, 8) ++ byteStringify(4, index, offset, length)
  }

  case class Port(port: Int) extends Message {
    lazy val toByteString = ByteString(0, 0, 0, 3, 9) ++ byteStringify(2, port)
  }

  case class Handshake(infoHash: ByteString, peerId: ByteString) extends Message {
    private lazy val reserved = ByteString(0, 0, 0, 0, 0, 0, 0, 0)
    lazy val toByteString = ByteString(19) ++ protocol ++ reserved ++
      infoHash ++ peerId
  }

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