package com.github.jerchung.submarine.core.peer

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.event.EventStream
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.{Core, Torrent}
import com.github.jerchung.submarine.core.protocol.TorrentProtocol
import com.github.jerchung.submarine.core.tracker.Tracker

object PeersGatekeeper {
  def props(args: Args): Props = {
    Props(new PeersGatekeeper(args) with PeersGatekeeper.AppCake)
  }

  trait Provider {
    def peersGatekeeper(args: Args): ActorRef
  }

  trait AppProvider extends Provider { this: Core.AppProvider =>
    override def peersGatekeeper(args: Args): ActorRef =
      context.actorOf(PeersGatekeeper.props(args))
  }

  trait Cake {
    def provider: Peer.Provider with ConnectingPeer.Provider
  }

  trait AppCake extends Cake { this: PeersGatekeeper =>
    override val provider = new Core.AppProvider(context)
      with Peer.AppProvider
      with ConnectingPeer.AppProvider
  }

  case class Args(torrent: Torrent,
                  clientId: ByteString,
                  torrentEvents: EventStream)

  case class InitiatePeer(protocol: ActorRef,
                          ip: String,
                          port: Int,
                          peerId: ByteString)
}

class PeersGatekeeper(args: PeersGatekeeper.Args) extends Actor with ActorLogging { this: PeersGatekeeper.Cake =>

  var connectedIps: Set[String] = Set()

  val infoHash: ByteString = ByteString.fromArray(args.torrent.infoHash)

  override def preStart(): Unit = {
    // Every time the tracker announces we're going to connect to more peers
    args.torrentEvents.subscribe(self, classOf[Tracker.Response.Announce])

    args.torrentEvents.subscribe(self, classOf[Peer.Announce])

    // PeerServer broadcasts connections with
    context.system.eventStream.subscribe(self, classOf[PeerServer.NewPeer])
  }

  def receive: Receive = {
    case Peer.Announce(_, publish) =>
      publish match {
        case Peer.Connected(peerArgs) =>
          connectedIps += peerArgs.ip
        case Peer.Disconnected(_, ip, _) =>
          connectedIps -= ip

        case _ => ()
      }

    case announce: Tracker.Response.Announce =>
      announce.peers.foreach { case (ip, port, peerId) =>
        if (!connectedIps.contains(ip)) {
          // Connecting peer will send an InitiatePeer message back to PeersGatekeeper after connection
          provider.connectingPeer(ConnectingPeer.Args(self, ip, port, infoHash, args.clientId))
        }
      }

    // Secondary case for initializing peers where remote peers are initiating connections to us (through the PeerServer)
    case PeersGatekeeper.InitiatePeer(protocol, ip, port, peerId) if !connectedIps.contains(ip) =>
      initPeer(protocol, ip, port, peerId)

    /**
      * Check that infoHash matches as this message comes broadcasted from the PeerServer, meaning an outside peer
      * is initializing the connection with us.  Once the handshake is verified to match, send a handshake back in return
      * to complete the 2 way handshake procedure.
      */
    case PeerServer.NewPeer(ip, port, protocol, _infoHash, peerId) if infoHash == _infoHash &&
                                                                     !connectedIps.contains(ip) =>
      val peer = initPeer(protocol, ip, port, peerId)
      peer ! TorrentProtocol.Send.Handshake(infoHash, args.clientId)

  }

  private def initPeer(protocol: ActorRef,
                       ip: String,
                       port: Int,
                       peerId: ByteString): ActorRef = {

    val peerArgs = Peer.Args(
      protocol,
      peerId,
      args.clientId,
      ip,
      port,
      args.torrent,
      args.torrentEvents
    )

    provider.peer(peerArgs)
  }
}
