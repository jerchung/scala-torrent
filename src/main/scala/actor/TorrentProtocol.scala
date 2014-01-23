package org.jerchung.torrent.actor

import org.jerchung.torrent.actor.message.{ PeerM, BT }
import akka.actor.{ Actor, ActorRef, Props, PoisonPill }
import akka.io.Tcp
import akka.util.ByteString
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import org.jerchung.torrent.Convert._
import org.jerchung.torrent.Constant
import scala.collection.BitSet

object TorrentProtocol {

  def props(connection: ActorRef): Props = {
    Props(classOf[TorrentProtocol], connection)
  }

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
  lazy val protocol = ByteString.fromString("BitTorrent protocol")
  lazy val handshake = ByteString(19) ++ protocol

  // Use number of pieces a particular torrent has to generate the bitfield
  // Bytes needed is numPieces / 8 since it's 8 bits per byte
  // numPieces needs to be passed in so that the number of padding 0's is known
  def bitfield(bitfield: BitSet, numPieces: Int): ByteString = {
    val bitValue = if (bitfield.isEmpty) 0 else bitfield.toBitMask.head
    val numBytesNeeded = math.ceil(numPieces.toFloat / 8).toInt
    val buffer = ByteBuffer.allocate(numBytesNeeded)
    val byteArray = buffer.putLong(bitValue).array
    ByteString.fromArray(byteArray)
  }

  // Just creating the prefix for ByteString
  def piece(length: Int): ByteString = {
    val byteArray = ByteBuffer.allocate(4).putInt(length + 9).array
    ByteString.fromArray(byteArray) ++ ByteString(7)
  }

}

/**
 * This actor servers to translate between ByteStrings and TCP Wire Messages.
 * The parent of this actor should always be a Peer Actor, thus responses are
 * send to parent
 */
class TorrentProtocol(connection: ActorRef) extends Actor {

  import context.parent

  override def preStart(): Unit = {
    connection ! Tcp.Register(self)
  }

  override def postRestart(reason: Throwable): Unit = {}

  def receive: Receive = {
    case msg: BT.Message => handleMessage(msg)
    case Tcp.Received(data) => handleReply(data)
  }

  // Take in an int and the # of bytes it should contain, return the
  // corresponding ByteString of the int with appropriate leading 0s
  // Works for multiple nums of the same size
  // Don't use ByteBuffer since I need speed.
  def byteStringify(size: Int, nums: Int*): ByteString = {
    val builder = ByteString.newBuilder
    for (n <- nums; idx <- 0 until size) {
      val shift = Constant.ByteSize * (size - 1 - idx)
      builder += ((n >> shift) & 0xFF).asInstanceOf[Byte]
    }
    builder.result
  }

  // Handle each type of tcp peer message that client may want to send
  // Create ByteString based off message type and send to tcp connection
  // TODO - BITFIELD
  def handleMessage(msg: BT.Message) = {
    val byteMessage: ByteString = msg match {
      case BT.KeepAlive                      => TorrentProtocol.keepAlive
      case BT.Choke                          => TorrentProtocol.choke
      case BT.Unchoke                        => TorrentProtocol.unchoke
      case BT.Interested                     => TorrentProtocol.interested
      case BT.NotInterested                  => TorrentProtocol.notInterested
      case BT.Bitfield(bitfield, numPieces)  => TorrentProtocol.bitfield(bitfield, numPieces)
      case BT.Have(index)                    => TorrentProtocol.have ++
                                                byteStringify(4, index)
      case BT.Request(index, offset, length) => TorrentProtocol.request ++
                                                byteStringify(4, index, offset, length)
      case BT.Piece(index, offset, block)    => TorrentProtocol.piece(block.size) ++
                                                byteStringify(4, index, offset) ++
                                                block
      case BT.Cancel(index, offset, length)  => TorrentProtocol.cancel ++
                                                byteStringify(4, index, offset, length)
      case BT.Port(port)                     => TorrentProtocol.port ++
                                                byteStringify(2, port)
      case BT.Handshake(info, id)            => TorrentProtocol.handshake ++
                                                info ++
                                                id
    }
    connection ! Tcp.Write(byteMessage)
  }

  // https://wiki.theory.org/BitTorrentSpecification
  // ByteString may come as multiple messages concatenated together, so parse
  // through the given bytestring and send messages to peerclient as they're 'translated'
  // ByteStrings are implicitly converted to Ints / Strings when needed
  def handleReply(data: ByteString): Unit = {
    if (!data.isEmpty) {
      var length: Int = 0
      var msg: Option[BT.Reply] = None
      if (data.head == 19 && data.slice(1, 20) == TorrentProtocol.protocol) {
        length = 68
        msg = Some(BT.HandshakeR(
          infoHash = data.slice(28, 48),
          peerId = data.slice(48, 68))
        )
      } else {
        length = data.take(4).toInt + 4
        if (length == 4) {
          msg = Some(BT.KeepAliveR)
        } else {
          msg = Some(parseCommonReply(data, length))
        }
      }

      parent ! msg.get
      handleReply(data.drop(length))
    }
  }

  // TODO - PUT THE IMPLICIT toInt, toLong, toBitSet calls here
  def parseCommonReply(data: ByteString, length: Int): BT.Reply = {
    data(4) match {
      case 0 => BT.ChokeR
      case 1 => BT.UnchokeR
      case 2 => BT.InterestedR
      case 3 => BT.NotInterestedR
      case 4 => BT.HaveR(data.slice(5, 9).toInt)
      case 5 => BT.BitfieldR(data.slice(5, length).toBitSet)
      case 6 => BT.RequestR(
        index = data.slice(5, 9).toInt,
        offset = data.slice(9, 13).toInt,
        length = data.slice(13, 17).toInt)
      case 7 => BT.PieceR(
        index = data.slice(5, 9).toInt,
        offset = data.slice(9, 13).toInt,
        block = data.slice(13, length))
      case 8 => BT.CancelR(
        index = data.slice(5, 9).toInt,
        offset = data.slice(9, 13).toInt,
        length = data.slice(13, 17).toInt)
      case 9 => BT.PortR(data.slice(5, 7).toInt)
      case _ => BT.InvalidR
    }
  }

}