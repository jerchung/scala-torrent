package com.github.jerchung.submarine.core.peer

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.EventStream
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.{Core, Torrent}
import com.github.jerchung.submarine.core.tracker.Tracker

object PeersCoordinator {
  def props(args: Args): Props = {
    Props(new PeersCoordinator(args) with PeersCoordinator.AppCake)
  }

  trait Provider extends Core.Cake#Provider {
    def peersSupervisor(args: Args): ActorRef
  }

  trait AppProvider extends Provider {
    override def peersSupervisor(args: Args): ActorRef =
      context.actorOf(PeersCoordinator.props(args))
  }

  trait Cake extends Core.Cake { this: PeersCoordinator =>
    def provider: Provider
    trait Provider
      extends PeerServer.Provider
      with Peer.Provider
      with ConnectingPeer.Provider
  }

  trait AppCake extends Cake { this: PeersCoordinator =>
    val provider = new Provider
      with PeerServer.AppProvider
      with Peer.AppProvider
      with ConnectingPeer.AppProvider
  }

  case class Args(torrent: Torrent,
                  port: Int,
                  clientId: ByteString,
                  torrentEvents: EventStream)

  case class InitiatePeer(connection: ActorRef,
                          ip: String,
                          port: Int)
}

class PeersCoordinator(args: PeersCoordinator.Args) extends Actor with ActorLogging { this: PeersCoordinator.AppCake =>

  val peersServer: ActorRef = provider.peerServer(PeerServer.Args(self, args.port))

  // Every time the tracker announces more peers we're going to connect to more peers
  override def preStart(): Unit = {
    args.torrentEvents.subscribe(self, classOf[Tracker.Response.Announce])
  }

  def receive: Receive = {
    // Given a tracker response, initiate a connecting peer which will then initiate a peer actor once connected
    case announce: Tracker.Response.Announce =>
      announce.peers.foreach { case (ip, port, peerId) =>
        provider.connectingPeer(ConnectingPeer.Args(
          ip,
          port,
          peerId,
          args.clientId,
          args.torrent,
          args.torrentEvents
        ))
      }

    // Secondary case for initializing peers where remote peers are initiating connections to us (through the PeerServer)
    case PeersCoordinator.InitiatePeer(connection, ip, port) =>
      provider.waitHandshakePeer(Peer.Args(
        connection,
        None,
        args.clientId,
        ip,
        port,
        args.torrent,
        args.torrentEvents
      ))
  }
}
