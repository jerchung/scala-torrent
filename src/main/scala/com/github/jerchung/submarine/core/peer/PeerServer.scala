package com.github.jerchung.submarine.core.peer

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.io.Tcp
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.Core
import com.github.jerchung.submarine.core.protocol.TorrentProtocol

object PeerServer {
  def props(args: Args): Props = {
    Props(new PeerServer(args) with AppCake)
  }

  case class Args(port: Int)

  trait Provider {
    def peerServer(args: Args): ActorRef
  }

  trait AppProvider extends Provider {
    this: Core.AppProvider =>
    def peerServer(args: Args): ActorRef =
      context.actorOf(PeerServer.props(args))
  }

  trait Cake extends Core.TcpService {
    this: PeerServer =>
    def provider: TorrentProtocol.Provider
  }

  trait AppCake extends Cake with Core.AppTcpService {
    this: PeerServer =>
    val provider = new Core.AppProvider(context) with TorrentProtocol.AppProvider
  }

  case class NewPeer(ip: String, port: Int, protocol: ActorRef, infoHash: ByteString, peerId: ByteString)
}

/**
  * There should only be one PeerServer initialized for all the TorrentClients.  When a connection comes in, we will only
  * know which TorrentClient it's a peer of when the handshake comes in with the infoHash, which can then be mapped to an
  * active torrent.  Once the handshake message comes in, a message is published to all the torrent clients through the
  * EventStream and then the client with the matching infoHash will take the connection and start a peer from it.
  * @param args
  */
class PeerServer(args: PeerServer.Args) extends Actor with ActorLogging {
    this: PeerServer.Cake =>

  override def preStart(): Unit = {
    tcp ! Tcp.Bind(self, new InetSocketAddress("127.0.0.1", args.port))
  }

  def receive: Receive = {
    case Tcp.Bound(localAddress) =>
      log.info(s"Bound at ${localAddress.getHostName}:${localAddress.getPort}")

    case Tcp.CommandFailed(_: Tcp.Bind) =>
      log.error(s"Unable to bind at 127.0.0.1:${args.port}")
      context.stop(self)

    case Tcp.Connected(remote, _) =>
      val ip = remote.getAddress.getHostAddress
      val port = remote.getPort

      val protocol = provider.torrentProtocol(TorrentProtocol.Args(sender))

      log.debug(s"Peer connecting from $ip:$port")

      /**
        * The sole purpose of this actor is to register itself with the torrent protocol and listen for a handshake message
        * from the connecting peer.  Once this connection is established, utilize the EventStream to broadcast a message
        * to all the torrent clients so that the client with the matching infoHash (if any) can begin using this peer.
        */
      context.actorOf(Props(new Actor {
        override def preStart(): Unit = {
          protocol ! TorrentProtocol.Command.SetPeer(self)
          context.watch(protocol)
        }

        override def receive: Receive = {
          case TorrentProtocol.Reply.Handshake(infoHash, peerId) =>
            context.system.eventStream.publish(PeerServer.NewPeer(ip, port, protocol, infoHash, peerId))
            context.stop(self)

          case Terminated(actor) if actor == protocol =>
            context.stop(self)
        }
      }))
  }
}
