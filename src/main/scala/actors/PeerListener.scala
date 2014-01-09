package org.jerchung.torrent

import ActorMessage.{ BT, TorrentM }
import akka.actor.{ Props, Actor, ActorRef }
import akka.io.{ IO, Tcp }
import akka.pattern.ask
import akka.util.Timeout
import java.net.InetSocketAddress
import scala.concurrent.duration._

object PeerListener {
  def props: Props = { Props(classOf[PeerListener]) }
}

// Listen for new connections made from peers
class PeerListener extends Actor {

  import context.{ system, become, parent, dispatcher }

  implicit val timeout = Timeout(5 seconds)

  // Figure out which port to bind to later - right now 0 defaults to random
  IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress("localhost", 0))

  def receive = {
    case b @ Tcp.Bound(localAddress) => // Implement logging later
    case Tcp.CommandFailed(_: Tcp.Bind) => // Binding failed
    case Tcp.Connected(remote, local) =>
      val protocol = context.actorOf(TorrentProtocol.props(sender, self))
      sender ! Tcp.Register(protocol)
      context become {
        case m: Tcp.Connected => self ! m // Need to wait for a handshake
        case BT.HandshakeR(infoHash, peerId) =>
          val peerF = (parent ? TorrentM.CreatePeer(infoHash, peerId)).mapTo[ActorRef]
          for {
            peer <- peerF
            done <- protocol ? BT.SetListener(peer)
          } yield {
            peer ! BT.Handshake(infoHash, peerId)
          }
          become(receive)
      }
  }

}