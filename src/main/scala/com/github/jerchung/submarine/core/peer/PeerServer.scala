package com.github.jerchung.submarine.core.peer

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.Tcp
import com.github.jerchung.submarine.core.base.Core
import storrent.peer

object PeerServer {
  def props(args: Args): Props = {
    Props(new PeerServer(args) with Core.AppParent with Core.AppTcpService)
  }

  case class Args(peersCoordinator: ActorRef, port: Int)

  trait Provider extends Core.Cake#Provider {
    def peerServer(args: Args): ActorRef
  }

  trait AppProvider extends Provider {
    def peerServer(args: Args): ActorRef =
      context.actorOf(PeerServer.props(args))
  }
}

class PeerServer(args: PeerServer.Args) extends Actor with ActorLogging {
    this: Core.Parent with Core.TcpService =>

  override def preStart(): Unit = {
    tcp ! Tcp.Bind(self, new InetSocketAddress("127.0.0.1", args.port))
  }

  def receive = {
    case Tcp.Bound(localAddress) =>
      log.info(s"Bound at ${localAddress.getHostName}:${localAddress.getPort}")

    case Tcp.CommandFailed(_:Tcp.Bind) =>
      log.error(s"Unable to bind at 127.0.0.1:${args.port}")
      context.stop(self)

    case Tcp.Connected(remote, _) =>
      val ip = remote.getHostName
      val port = remote.getPort
      log.debug(s"Peer connecting from $ip:$port")

      args.peersCoordinator ! PeersCoordinator.InitiatePeer(sender, ip, port)
  }
}
