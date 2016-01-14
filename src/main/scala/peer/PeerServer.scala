package storrent.peer

import akka.actor.{ Actor, ActorLogging, Props, ActorRef, Terminated }
import akka.io.{ IO, Tcp }
import java.net.InetSocketAddress

import storrent.core.Core
import storrent.core.PeersManager
import storrent.core.ScalaTorrent
import storrent.Constant
import storrent.peer
import storrent.message.{ BT }

object PeerServer {
  def props(port: Int, coordinator: ActorRef): Props = {
    Props(new PeerServer(port, coordinator) with Core.AppParent with Core.AppTcpManager)
  }
}

class PeerServer(port: Int, coordinator: ActorRef) extends Actor with ActorLogging {
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

      // Start a Peer Connectino Handler which will create a protocol and wait
      // for handshake message to come with infoHash to register with the
      // correct torrent client
      context.actorOf(Props(new HandlePeerConnection(sender, ip, port, coordinator)))
  }
}

class HandlePeerConnection(connection: ActorRef, ip: String, port: Int, coordinator: ActorRef)
    extends Actor
    with ActorLogging {

  override def preStart(): Unit = {
    val protocol = context.actorOf(TorrentProtocol.props(sender, Set(self)))
    context.watch(protocol)
  }

  def receive: Receive = {
    case BT.HandshakeR(infoHash, peerId) =>
      sender ! TorrentProtocol.Unsubscribe(self)
      coordinator ! ScalaTorrent.OutsidePeerConnect(sender, ip, port, infoHash, peerId)

    case t: Terminated =>
      context.stop(self)
  }
}
