package storrent.peer

import akka.io.{ IO, Tcp }
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.ByteString
import java.net.InetSocketAddress
import storrent.message.TorrentM
import storrent.core.Core._
import storrent.peer

object ConnectingPeer {
  def props(ip: String, port: Int, peerId: Option[ByteString]): Props = {
    Props(new ConnectingPeer(ip, port, peerId) with AppParent with AppTcpManager)
  }
}

class ConnectingPeer(ip: String, port: Int, peerId: Option[ByteString])
  extends Actor with ActorLogging {
  this: Parent with TcpManager =>

  override def preStart(): Unit = {
    val remote = new InetSocketAddress(ip, port)
    tcpManager ! Tcp.Connect(remote)
  }

  def receive = {
    case Tcp.Connected(remote, _) =>
      log.info(s"Connected to $remote")
      val connection = sender
      parent ! TorrentM.CreatePeer(connection, peerId, ip, port, peer.InitHandshake)
      // Don't stop self because then the connection gets stopped
    case _ =>
      context.stop(self)
  }
}
