package storrent.peer

import akka.actor.{ Actor, ActorLogging, Props, ActorRef }
import akka.io.{ IO, Tcp }
import java.net.InetSocketAddress

import storrent.core.Core
import storrent.Constant
import storrent.message.TorrentM
import storrent.peer

object PeerServer {
  def props(port: Int): Props = {
    Props(new PeerServer(port) with Core.AppParent with Core.AppTcpManager)
  }
}

class PeerServer(port: Int) extends Actor with ActorLogging {
    this: Core.Parent with Core.TcpManager =>

  override def preStart(): Unit = {
    tcpManager ! Tcp.Bind(self, new InetSocketAddress("127.0.0.1", port))
  }

  def receive = {
    case Tcp.Bound(localAddress) =>
      log.info(s"Bound at ${localAddress.getHostName}:${localAddress.getPort}")

    case Tcp.CommandFailed(_:Tcp.Bind) =>
      log.error(s"Unable to bind at 127.0.0.1:$port")
      context.stop(self)

    case Tcp.Connected(remote, local) =>
      val ip = remote.getHostName
      val port = remote.getPort
      log.info(s"Peer connecting from $ip:$port")
      parent ! TorrentM.CreatePeer(sender, None, ip, port, peer.WaitHandshake)
  }
}
