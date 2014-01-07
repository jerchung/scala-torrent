package org.jerchung.torrent

import akka.actor.{ Actor, ActorRef, Props, Receive }
import AM.{ PeerM, BT }
import akka.util.ByteString
import akka.io.{ IO, Tcp }
import java.net.InetSocketAddress
import java.nio.ByteBuffer

object TorrentProtocol {
  def props(remote: InetSocketAddress) =
    Props(classOf[TorrentProtocol], remote)

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

class TorrentProtocol(remote: InetSocketAddress) extends Actor {

  import context.{ become, parent }

  var connection: ActorRef = _
  IO(Tcp) ! Tcp.Connect(remote)

  implicit def ByteStringToInt(data: ByteString): Int = {
    ByteBuffer.wrap(data.toArray).getInt
  }

  // Take in an int and the # of bytes it should contain, return the
  // corresponding ByteString
  // Works for multiple nums of the same size
  def byteStringify(size: Int, nums: Int*): ByteString = {
    val byteStrings: List[ByteString] = nums map { n =>
      val byteArray = Array.fill[Byte](size)(0)
      ByteBuffer.wrap(byteArray).putInt(n)
      ByteString.fromArray(byteArray)
    }
    byteStrings.foldLeft(ByteString()) { (acc, cur) => acc ++ cur }
  }

  def receive = {
    case Tcp.CommandFailed(_) =>
      parent ! "Failureee"
      context stop self
    case Tcp.Connected(remote, local) =>
      connection = sender
      connection ! Tcp.Register(self)
      parent ! BT.Connected
      become(connected)
  }

  def connected: Receive = {
    case msg: BT.Message => handleMessage(msg)
    case Tcp.Received(data) => handleReply(data)
  }

  // Handle each type of tcp peer message that client may want to send
  // Create ByteString based off message type and send to tcp connection
  def handleMessage(msg: BT.Message) = {
    val data: ByteString = msg match {
      case BT.KeepAlive => TorrentProtocol.keepAlive
      case BT.Choke => TorrentProtocol.choke
      case BT.Unchoke => TorrentProtocol.unchoke
      case BT.Interested => TorrentProtocol.interested
      case BT.NotInterested => TorrentProtocol.notInterested
      case BT.Have(index) => TorrentProtocol.have ++ byteStringify(4, index)
      case BT.Request(index, begin, length) => TorrentProtocol.request
        ++ byteStringify(4, index, begin, length)
      case BT.Piece(index, begin, block) => ByteString(0, 0, 0, 9 + block.length, 7)
        ++ byteStringify(4, index, begin) ++ block
      case BT.Cancel(index, begin, length) => TorrentProtocol.cancel
        ++ byteStringify(4, index, begin, length)
      case BT.Port(port) => TorrentProtocol.port ++ byteStringify(2, port)
      case BT.Handshake(info, id) => TorrentProtocol.handshake ++ info ++ id
    }
    connection ! Tcp.Write(data)
  }

  // https://wiki.theory.org/BitTorrentSpecification
  // ByteString may come as multiple messages concatenated together, so parse
  // through the given bytestring and send messages to peerclient as they're 'translated'
  // ByteStrings are implicitly converted to Ints when needed
  def handleReply(data: ByteString) = {
    if (!data.isEmpty) {
      var length: Int = 0
      var msg: BT.Reply = _
      if (data.head == 19 && data.slice(1, 20)) {
        length = 68
        msg = BT.HandshakeR(
          infoHash = data.slice(28, 48),
          peerId = data.sice(48, 68))
      } else {
        length = data.take(4) + 4
        if (length == 4) {
          msg = BT.KeepAliveR
        } else {
          msg = data(4) match {
            case 0 => BT.ChokeR
            case 1 => BT.UnchokeR
            case 2 => BT.InterestedR
            case 3 => BT.NotInterestedR
            case 4 => BT.HaveR(data.slice(5, 9))
            case 5 => BT.BitfieldR
            case 6 => BT.RequestR(
              index = data.slice(5, 9),
              begin = data.slice(9, 13),
              length = data.slice(13, 17))
            case 7 => BT.PieceR(
              index = data.slice(5, 9),
              begin = data.slice(9, 13),
              block = data.slice(13, length))
            case 8 => BT.CancelR(
              index = data.slice(5, 9),
              begin = data.slice(9, 13),
              length = data.slice(13, 17))
            case 9 => BT.PortR(data.slice(5, 7))
          }
        }
      }

      connection ! msg
      handleReply(data.drop(length))
    }
  }

}