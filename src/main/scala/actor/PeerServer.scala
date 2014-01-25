package org.jerchung.torrent.actor

import org.jerchung.torrent.actor.message.{ BT, TorrentM }
import akka.actor.{ Props, Actor, ActorRef }
import akka.io.Tcp
import akka.pattern.ask
import akka.util.Timeout
import java.net.InetSocketAddress
import scala.concurrent.duration._

object PeerServer {
  def props(manager: ActorRef): Props = {
    Props(classOf[PeerServer], manager)
  }
}

// Listen for new connections made from peers
// Parent is Torrent Client
class PeerServer(manager: ActorRef) extends Actor {

  import context.{ system, become, parent, dispatcher }

  override def preStart(): Unit = {
    // Figure out which port to bind to later - right now 0 defaults to random
    manager ! Tcp.Bind(self, new InetSocketAddress("localhost", 0))
  }

  /**
   * Keep bind call from being re-sent to IO(Tcp).  According to akka documentation
   * upon actor restart, preStart is called from postRestart by default, so
   * override it so that the preRestart call is only sent upon initial actor
   * creation
   */
  override def postRestart(reason: Throwable): Unit = {}

  def receive = {
    case b @ Tcp.Bound(localAddress) => // Implement logging later
    case Tcp.CommandFailed(_: Tcp.Bind) => // Binding failed
    case Tcp.Connected(remote, local) =>
      val peer = sender
      parent ! TorrentM.CreatePeer(peer, remote)
  }

}