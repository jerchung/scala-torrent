package storrent.peer

import akka.io.{ IO, Tcp }
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.ByteString
import java.net.InetSocketAddress
import storrent.message.TorrentM
import storrent.core.Core._

object ConnectingPeer {
  def props(peerInfo: PeerInfo): Props = {
    Props(new ConnectingPeer(peerInfo) with AppParent with AppTcpManager)
  }
}

class ConnectingPeer(peerInfo: PeerInfo)
    extends Actor {
  this: Parent with TcpManager =>

  import context.system

  override def preStart(): Unit = {
    val remote = new InetSocketAddress(peerInfo.ip, peerInfo.port)
    tcpManager ! Tcp.Connect(remote)
  }

  def receive = {
    case Tcp.Connected(remote, _) =>
      println(s"Connected to $remote")
      val connection = sender
      // connection ! Tcp.Register(self)
      parent ! TorrentM.CreatePeer(connection, peerInfo)
      // context stop self
    case _ =>
      // context stop self
  }
}
