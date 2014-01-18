package org.jerchung.torrent.actor

import ActorMessage.{ BT, TorrentM }
import akka.actor.{ Props, Actor, ActorRef }
import akka.io.{ IO, Tcp }
import akka.pattern.ask
import akka.util.Timeout
import java.net.InetSocketAddress
import scala.concurrent.duration._

object PeerServer {
  def props(manager: ActorRef): Props = { Props(classOf[PeerServer], manager) }
}

// Listen for new connections made from peers
class PeerServer(manager: ActorRef) extends Actor {

  import context.{ system, become, parent, dispatcher }

  implicit val timeout = Timeout(5 seconds)

  // Figure out which port to bind to later - right now 0 defaults to random
  IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress("localhost", 0))

  def receive = {
    case b @ Tcp.Bound(localAddress) => // Implement logging later
    case Tcp.CommandFailed(_: Tcp.Bind) => // Binding failed
    case Tcp.Connected(remote, local) =>
      val connection = sender
      val protocol = context.actorOf(TorrentProtocol.props(connection))
      manager ! WaitForHandshake.props(protocol)
  }

}

object WaitForHandshake {
  def props(protocol: ActorRef): Props = Props(classOf[WaitForHandshake], protocol)
}

/* Parent must be TorrentClient */
class WaitForHandshake(protocol: ActorRef) extends Actor {

  // Register self with TorrentProtocol actor upon intialization
  override def preStart(): Unit = {
    protocol ! BT.Listener(self)
  }

  def receive = {
    case BT.HandshakeR(infoHash, peerId) =>
      manager ! PeerClient.props(Peer(peerId, id, infoHash), protocol)
  }

}