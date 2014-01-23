package org.jerchung.torrent.actor

import akka.io.{ IO, Tcp }
import akka.actor.Actor
import akka.util.ByteString
import java.net.InetSocketAddress

object ConnectingPeer {
  def props(remote: InetSocketAddress, peerId: ByteString): Props = {
    Props(classOf[ConnectingPeer], remote)
  }
}

class ConnectingPeer(remote: InetSocketAddress, peerId: ByteString) extends Actor {

  IO(Tcp) ! Tcp.Connect(remote)

  def receive = {
    case Tcp.Connected(remote, local) =>
      context.parent ! CreatePeer(sender, remote, Some(peerId))
      context stop self
    case _ => context stop self
  }
}