package org.jerchung.torrent.actor

import akka.io.{ IO, Tcp }
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.ByteString
import java.net.InetSocketAddress
import org.jerchung.torrent.actor.message.TorrentM

// sealed trait ConnPeerProvider extends Parent with TcpManager

// sealed trait ProdConnPeerProvider extends ProdParent with ProdTcpManager {
//   this: Actor =>
// }

object ConnectingPeer {
  def props(remote: InetSocketAddress, peerId: ByteString): Props = {
    Props(new ConnectingPeer(remote, peerId) with ProdParent with ProdTcpManager)
  }
}

class ConnectingPeer(remote: InetSocketAddress, peerId: ByteString)
    extends Actor { this: Parent with TcpManager =>

  override def preStart(): Unit = {
    tcpManager ! Tcp.Connect(remote)
  }

  def receive = {
    case Tcp.Connected(remote, local) =>
      parent ! TorrentM.CreatePeer(sender, remote, Some(peerId))
      context stop self
    case _ => context stop self
  }
}