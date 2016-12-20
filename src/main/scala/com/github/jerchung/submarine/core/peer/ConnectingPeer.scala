package com.github.jerchung.submarine.core.peer

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.event.EventStream
import akka.io.Tcp
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.Core.{AppTcpService, TcpService}
import com.github.jerchung.submarine.core.base.{Core, Torrent, TorrentClient}

object ConnectingPeer {
  def props(args: Args): Props = {
    Props(new ConnectingPeer(args) with AppCake with AppTcpService)
  }

  trait Cake extends Core.Cake { this: ConnectingPeer =>
    def provider: Provider
    trait Provider extends Peer.Provider
  }

  trait AppCake extends Cake { this: ConnectingPeer =>
    val provider = new Provider with Peer.AppProvider
  }

  case class Args(ip: String,
                  port: Int,
                  peerId: Option[ByteString],
                  ownId: ByteString,
                  torrent: Torrent,
                  torrentEvents: EventStream)

  trait Provider extends Core.Cake#Provider {
    def connectingPeer(args: Args): ActorRef
  }

  trait AppProvider extends Provider {
    def connectingPeer(args: Args): ActorRef =
      context.actorOf(ConnectingPeer.props(args))
  }
}

class ConnectingPeer(args: ConnectingPeer.Args) extends Actor with ActorLogging {
  this: ConnectingPeer.Cake with TcpService =>

  override def preStart(): Unit = {
    val remote = new InetSocketAddress(args.ip, args.port)
    tcp ! Tcp.Connect(remote)
  }

  // TODO(jerry) - Set supervisor strategy to ESCALATEEE

  def receive: Receive = {
    case Tcp.Connected(_, _) =>
      val peerArgs = Peer.Args(
        sender,
        args.peerId,
        args.ownId,
        args.ip,
        args.port,
        args.torrent,
        args.torrentEvents
      )

      val peer = provider.handshakeInitPeer(peerArgs)
      context.watch(peer)
    case Terminated(_) =>
      context.stop(self)
    case _ =>
      context.stop(self)
  }
}
