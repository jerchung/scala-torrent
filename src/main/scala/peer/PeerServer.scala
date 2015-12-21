package storrent.peer

import akka.actor.{ Actor, ActorLogging, Props, ActorRef }
import akka.io.{ IO, Tcp }
import java.net.InetSocketAddress

import storrent.core.Core
import storrent.core.PeersManager
import storrent.Constant
import storrent.peer

object PeerServer {
  def props(port: Int, router: ActorRef, manager: ActorRef): Props = {
    Props(new PeerServer(port, router, manager) with Core.AppParent with Core.AppTcpManager)
  }
}

class PeerServer(port: Int, router: ActorRef, manager: ActorRef) extends Actor with ActorLogging {
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
      manager ! PeersManager.ConnectedPeer(sender, ip, port, None, peer.WaitHandshake, router)
  }
}
