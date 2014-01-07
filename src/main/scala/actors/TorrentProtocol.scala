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
    val data = msg match {
      case BT.KeepAlive => ByteString(0, 0, 0, 0)
      case BT.Choke => ByteString(0, 0, 0, 1, 0)
      case BT.Unchoke => ByteString(0, 0, 0, 1, 1)
      case BT.Interested => ByteString(0, 0, 0, 1, 2)
      case BT.NotInterested => ByteString(0, 0, 0, 1, 3)
      case BT.Have(index) => ByteString(0, 0, 0, 5, 4) ++ byteStringify(4, index)
      case BT.Request(index, begin, length) => ByteString(0, 0, 0, 13, 6)
        ++ byteStringify(4, index, begin, length)
      case BT.Piece(index, begin, block) => ByteString(0, 0, 0, 9 + block.length, 7)
        ++ byteStringify(4, index, begin) ++ block
      case BT.Cancel(index, begin, length) => ByteString(0, 0, 0, 13, 8)
        ++ byteStringify(4, index, begin, length)
      case BT.Port(port) => ByteString(0, 0, 0, 3, 9) ++ byteStringify(2, port)
      case BT.Handshake(info, id) => ByteString(19)
        ++ ByteString.fromString("BitTorrent protocol") ++ info ++ id
    }
    connection ! Tcp.Write(data)
  }

  // https://wiki.theory.org/BitTorrentSpecification
  // ByteString may come as multiple messages concatenated together, so return
  // a list of messages which will then be passed up to PeerClient
  // ByteStrings are implicitly converted to Ints when needed
  def handleReply(data: ByteString) = {
    if (!data.isEmpty) {
      var length: Int = 0
      var msg: BT.Reply.Reply = _
      if (data.head == 19 && data.slice(1, 20)) {
        length = 68
        msg = BT.Reply.Handshake(
          infoHash = data.slice(28, 48),
          peerId = data.sice(48, 68))
      } else {
        length = data.take(4) + 4
        if (length == 4) {
          msg = BT.KeepAlive
        } else {
          msg = data(4) match {
            case 0 => BT.Reply.Choke
            case 1 => BT.Reply.Unchoke
            case 2 => BT.Reply.Interested
            case 3 => BT.Reply.NotInterested
            case 4 => BT.Reply.Have(data.slice(5, 9))
            case 5 => BT.Reply.Bitfield
            case 6 => BT.Reply.Request(
              index = data.slice(5, 9),
              begin = data.slice(9, 13),
              length = data.slice(13, 17))
            case 7 => BT.Reply.Piece(
              index = data.slice(5, 9),
              begin = data.slice(9, 13),
              block = data.slice(13, length))
            case 8 => BT.Reply.Cancel(
              index = data.slice(5, 9),
              begin = data.slice(9, 13),
              length = data.slice(13, 17))
            case 9 => BT.Reply.Port(data.slice(5, 7))
          }
        }
      }

      connection ! msg
      handleReply(data.drop(length))
    }
  }

}