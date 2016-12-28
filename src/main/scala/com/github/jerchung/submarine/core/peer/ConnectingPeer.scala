package com.github.jerchung.submarine.core.peer

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.io.Tcp
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.Core.{AppTcpService, TcpService}
import com.github.jerchung.submarine.core.base.Core
import com.github.jerchung.submarine.core.protocol.TorrentProtocol

object ConnectingPeer {
  def props(args: Args): Props = {
    Props(new ConnectingPeer(args) with AppCake)
  }

  case class Args(peersGateKeeper: ActorRef,
                  ip: String,
                  port: Int,
                  infoHash: ByteString,
                  clientId: ByteString)

  trait Provider {
    def connectingPeer(args: Args): ActorRef
  }

  trait AppProvider extends Provider { this: Core.AppProvider =>
    def connectingPeer(args: Args): ActorRef =
      context.actorOf(ConnectingPeer.props(args))
  }

  trait Cake extends TcpService { this: ConnectingPeer =>
    def provider: TorrentProtocol.Provider
  }

  trait AppCake extends Cake with AppTcpService { this: ConnectingPeer =>
    val provider = new Core.AppProvider(context) with TorrentProtocol.AppProvider
  }
}

class ConnectingPeer(args: ConnectingPeer.Args) extends Actor with ActorLogging {
  this: ConnectingPeer.Cake =>

  override def preStart(): Unit = {
    val remote = new InetSocketAddress(args.ip, args.port)
    tcp ! Tcp.Connect(remote)
  }

  // TODO(jerry) - Set supervisor strategy to ESCALATEEE

  def receive: Receive = {
    case Tcp.Connected(_, _) =>
      val protocol: ActorRef = provider.torrentProtocol(TorrentProtocol.Args(sender))

      context.actorOf(Props(new Actor {
        override def preStart(): Unit = {
          protocol ! TorrentProtocol.Command.SetPeer(self)
          protocol ! TorrentProtocol.Send.Handshake
          context.watch(protocol)
        }

        override def receive: Receive = {
          case TorrentProtocol.Reply.Handshake(infoHash, peerId) if infoHash == args.infoHash =>
            args.peersGateKeeper ! PeersGatekeeper.InitiatePeer(
              protocol = protocol,
              ip = args.ip,
              port = args.port,
              peerId = peerId
            )
        }
      }))
    case Terminated(_) =>
      context.stop(self)
    case _ =>
      context.stop(self)
  }
}
