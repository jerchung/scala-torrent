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
    val data: ByteString = msg match {
      case BT.KeepAlive => Resource.keepAlive
      case BT.Choke => Resource.choke
      case BT.Unchoke => Resource.unchoke
      case BT.Interested => Resource.interested
      case BT.NotInterested => Resource.notInterested
      case BT.Have(index) => Resource.have ++ byteStringify(4, index)
      case BT.Request(index, begin, length) => Resource.request
        ++ byteStringify(4, index, begin, length)
      case BT.Piece(index, begin, block) => ByteString(0, 0, 0, 9 + block.length, 7)
        ++ byteStringify(4, index, begin) ++ block
      case BT.Cancel(index, begin, length) => Resource.cancel
        ++ byteStringify(4, index, begin, length)
      case BT.Port(port) => Resource.port ++ byteStringify(2, port)
      case BT.Handshake(info, id) => Resource.handshake ++ info ++ id
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