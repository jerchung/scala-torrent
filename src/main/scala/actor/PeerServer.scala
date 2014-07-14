package org.jerchung.torrent.actor

import org.jerchung.torrent.actor.message.{ BT, TorrentM }
import akka.actor.{ Props, Actor, ActorRef }
import akka.io.IO
import akka.io.Tcp
import akka.pattern.ask
import akka.util.Timeout
import java.net.InetSocketAddress
import com.escalatesoft.subcut.inject._
import org.jerchung.torrent.dependency.BindingKeys
import scala.concurrent.duration._

object PeerServer {
  def props(implicit bindingModule: BindingModule): Props = {
    Props(new PeerServer)
  }
}

// Listen for new connections made from peers
// Parent is Torrent Client
class PeerServer extends Actor with AutoInjectable {

  import BindingKeys._
  import context.system

  val tcpManager = injectOptional [ActorRef](TcpId) getOrElse { IO(Tcp) }
  val parent = injectOptional [ActorRef](ParentId) getOrElse { context.parent }

  override def preStart(): Unit = {
    // Figure out which port to bind to later - right now 0 defaults to random
    tcpManager ! Tcp.Bind(self, new InetSocketAddress("localhost", 0))
  }

  /**
   * Keep bind call from being re-sent to IO(Tcp).  According to akka documentation
   * upon actor restart, preStart is called from postRestart by default, so
   * override it so that the preStart call is only sent upon initial actor
   * creation
   */
  override def postRestart(reason: Throwable): Unit = {}

  def receive = {
    case b @ Tcp.Bound(localAddress) => // Implement logging later
    case Tcp.CommandFailed(_: Tcp.Bind) => // Binding failed
    case Tcp.Connected(remote, local) =>
      parent ! TorrentM.CreatePeer(sender, remote)
  }

}