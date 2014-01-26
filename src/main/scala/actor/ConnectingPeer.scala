package org.jerchung.torrent.actor

import akka.io.{ IO, Tcp }
import akka.actor.Actor
import akka.actor.Props
import akka.util.ByteString
import java.net.InetSocketAddress
import org.jerchung.torrent.actor.message.TorrentM

object ConnectingPeer {
  def props(remote: InetSocketAddress, peerId: ByteString): Props = {
    Props(new ConnectingPeer(remote, peerId) with ProdParent)
  }
}

class ConnectingPeer(remote: InetSocketAddress, peerId: ByteString)
    extends Actor { this: Parent =>

  import context.system

  IO(Tcp) ! Tcp.Connect(remote)

  def receive = {
    case Tcp.Connected(remote, local) =>
      parent ! TorrentM.CreatePeer(sender, remote, Some(peerId))
      context stop self
    case _ => context stop self
  }
}