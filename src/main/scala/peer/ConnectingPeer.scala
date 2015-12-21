package storrent.peer

import akka.io.{ IO, Tcp }
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Terminated
import akka.pattern.ask
import akka.util.ByteString
import akka.util.Timeout
import java.net.InetSocketAddress
import storrent.message.TorrentM
import storrent.core.Core._
import storrent.core.PeersManager

import scala.concurrent.duration._

object ConnectingPeer {
  def props(
      ip: String,
      port: Int,
      peerId: Option[ByteString],
      router: ActorRef,
      manager: ActorRef): Props = {
    Props(new ConnectingPeer(ip, port, peerId, router, manager)
              with AppParent
              with AppTcpManager)
  }
}

class ConnectingPeer(
    ip: String,
    port: Int,
    peerId: Option[ByteString],
    router: ActorRef,
    manager: ActorRef)
  extends Actor with ActorLogging {
  this: Parent with TcpManager =>

  import context.dispatcher

  implicit val timeout = Timeout(5.seconds)

  override def preStart(): Unit = {
    val remote = new InetSocketAddress(ip, port)
    tcpManager ! Tcp.Connect(remote)
  }

  def receive = {
    case Tcp.Connected(remote, _) =>
      log.info(s"Connected to $remote")
      (manager ? PeersManager.ConnectedPeer(sender, ip, port, peerId, InitHandshake, router))
        .mapTo[ActorRef]
        .foreach { context.watch }
      // Don't stop self until peer stops because then the connection gets stopped
    case Terminated(peer) =>
      context.stop(self)
    case _ =>
      context.stop(self)
  }
}
