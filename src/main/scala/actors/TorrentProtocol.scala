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

  implicit def ByteStringToInt(data: ByteString): Int = {
    ByteBuffer.wrap(data.toArray).getInt
  }

  import context.{ become, parent }

  var connection: ActorRef = _
  IO(Tcp) ! Tcp.Connect(remote)

  def receive = {
    case Tcp.CommandFailed(_) =>
      parent ! "Failureee"
      context stop self
    case Tcp.Connected(remote, local) =>
      connection = sender
      connection ! Register(self)
      parent ! Peer.Connected
      become(connected)
  }

  def connected: Receive = {
    case BT.Handshake(infoHash, peerId) => handshake(infoHash, peerId)
    case Tcp.Received(data) => parse(data) foreach { msg => parent ! msg }
  }

  def handshake(info: ByteString, id: ByteString) = {
    val msg = ByteString(19) ++ ByteString.fromString("BitTorrent protocol") ++
      infoHash ++ peerId
    connection ! Tcp.Write(msg)
  }

  // https://wiki.theory.org/BitTorrentSpecification
  // ByteString may come as multiple messages concatenated together, so return
  // a list of messages which will then be passed up to PeerClient
  def parse(data: ByteString, messages: List[BTMessage] = List()): List[BTMessage] = {
    if (data.isEmpty) {
      messages
    } else {
      val length: Int = data.take(4)
      val msg = if (length == 0) {
        KeepAlive
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
            )
        }
      }
      parse(data.slice(4 + length, data.length), msg +: messages)
    }
  }

}