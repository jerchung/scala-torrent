package org.jerchung.torrent.actor

import akka.io.{ IO, Tcp }
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.ByteString
import java.net.InetSocketAddress
import com.escalatesoft.subcut.inject._
import org.jerchung.torrent.actor.message.TorrentM
import org.jerchung.torrent.dependency.BindingKeys

object ConnectingPeer {
  def props(remote: InetSocketAddress, peerId: ByteString): Props = {
    Props(new ConnectingPeer(remote, peerId))
  }
}

class ConnectingPeer(remote: InetSocketAddress, peerId: ByteString)
    extends Actor
    with AutoInjectable {

  import BindingKeys._
  import context.system

  val tcpManager = injectOptional [ActorRef](TcpId) getOrElse { IO(Tcp) }
  val parent = injectOptional [ActorRef](TcpId) getOrElse { context.parent }

  override def preStart(): Unit = {
    tcpManager ! Tcp.Connect(remote)
  }

  def receive = {
    case Tcp.Connected(remote, local) =>
      parent ! TorrentM.CreatePeer(sender, remote, Some(peerId))
      context stop self
    case _ => context stop self
  }
}