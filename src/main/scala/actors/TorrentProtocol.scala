package org.jerchung.torrent

import akka.actor.{ Actor, ActorRef, Props, Receive }
import AM.{ Peer, BT }
import akka.util.ByteString
import akka.io.{ IO, Tcp }
import java.net.InetSocketAddress
import java.nio.ByteBuffer

object TorrentProtocol {
  def props(remote: InetSocketAddress) =
    Props(classOf[TorrentProtocol], remote)
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
  def byteStringify(num: Int, size: Int): ByteString = {
    val holder = Array.fill[Byte](size)(0)
    ByteBuffer.wrap(holder).putInt(num)
    ByteString.fromArray(holder)
  }

  def receive = {
    case Tcp.CommandFailed(_) =>
      parent ! "Failureee"
      context stop self
    case Tcp.Connected(remote, local) =>
      connection = sender
      connection ! Register(self)
      parent ! BT.Connected
      become(connected)
  }

  def connected: Receive = {
    case msg: BT.BTMessage => handle(msg)
    case Tcp.Received(data) => parse(data) foreach { msg => parent ! msg }
  }

  // Handle each type of tcp peer message that client may want to send
  // Create ByteString based off message type and send to tcp connection
  def handle(msg: BT.BTMessage) = {
    val data = msg match {
      case BT.KeepAlive => ByteString(0, 0, 0, 0)
      case BT.Choke => ByteString(0, 0, 0, 1, 0)
      case BT.Unchoke => ByteString(0, 0, 0, 1, 1)
      case BT.Interested => ByteString(0, 0, 0, 1, 2)
      case BT.NotInterested => ByteString(0, 0, 0, 1, 3)
      case BT.Have(index) => ByteString(0, 0, 0, 5, 4) ++ byteStringify(index, 4)
      case BT.Request(index, begin, length) => ByteString(0, 0, 0, 13, 6)
        ++ byteStringify(index, 4) ++ byteStringify(begin, 4)
        ++ byteStringify(length, 4)
      case BT.Piece()
    }
  }

  def handshake(info: ByteString, id: ByteString) = {
    val msg = ByteString(19) ++ ByteString.fromString("BitTorrent protocol") ++
      infoHash ++ peerId
    connection ! Tcp.Write(msg)
  }

  // https://wiki.theory.org/BitTorrentSpecification
  // ByteString may come as multiple messages concatenated together, so return
  // a list of messages which will then be passed up to PeerClient
  // ByteStrings are implicitly converted to Ints when needed
  def parse(data: ByteString, messages: List[BTMessage] = List()): List[BTMessage] = {
    if (data.isEmpty) {
      messages
    } else {
      val length: Int = data.take(4)
      val msg = if (length == 0) {
        BT.KeepAlive
      } else {
        data(4) match {
          case 0 => BT.Choke
          case 1 => BT.Unchoke
          case 2 => BT.Interested
          case 3 => BT.NotInterested
          case 4 => BT.Have(data.slice(5, 9))
          case 5 => BT.Bitfield
          case 6 => BT.Request(
            index = data.slice(5, 9),
            begin = data.slice(9, 13),
            length = data.slice(13, 17))
          case 7 => BT.Piece(
            index = data.slice(5, 9),
            begin = data.slice(9, 13),
            block = data.slice(13, length + 4))
          case 8 => BT.Cancel(
            index = data.slice(5, 9),
            begin = data.slice(9, 13),
            length = data.slice(13, 17))
          case 9 => BT.Port(port = data.slice(5, 7))
        }
      }
      parse(data.slice(4 + length, data.length), msg +: messages)
    }
  }

}