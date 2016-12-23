package com.github.jerchung.submarine.core.state

import akka.actor.{Actor, ActorRef, Props}
import akka.event.EventStream
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.Torrent
import com.github.jerchung.submarine.core.peer.Peer
import com.github.jerchung.submarine.core.implicits.Convert._

object TorrentState {
  def props(args: Args): Props = {
    Props(new TorrentState(args))
  }

  trait Relevant

  case class Args(torrentId: Int,
                  torrent: Torrent,
                  torrentEvents: EventStream)

  object Peer {
    case class Metadata(id: String,
                        ip: String,
                        port: Int)
  }

  object Aggregated {
    case class Metadata(totalDownloaded: Int,
                        totalUploaded: Int,
                        totalSize: Int)
  }

  sealed trait Request
  object Request {
    case object CurrentState extends Request
  }

  sealed trait Response
  object Response {
    case class CurrentState(aggregated: Aggregated.Metadata,
                            peers: Seq[Peer.Metadata])
  }
}

class TorrentState(args: TorrentState.Args) extends Actor {

  var peers: Map[ActorRef, TorrentState.Peer.Metadata] = Map()

  override def preStart(): Unit = {
    args.torrentEvents.subscribe(self, classOf[TorrentState.Relevant])
  }

  def receive: Receive = {
    case Peer.Announce(peer, publish) =>
      publish match {
        case Peer.Connected(peerArgs) =>
          val (peerId, ip, port) = (peerArgs.peerId.getOrElse(ByteString()).toChars, peerArgs.ip, peerArgs.port)
          val peerMetadata = TorrentState.Peer.Metadata(peerId, ip, port)

          peers += (peer -> peerMetadata)

        case _: Peer.Disconnected =>
          peers -= peer
      }
  }

  def receive: Receive = {
    case component: PieceDispatch.State =>
      comprehensiveState.piecesState = component
      peers = infos.map { case (actorRef, config) =>
        actorRef -> (config, rates.getOrElse(actorRef, 0f), seeds.contains(actorRef))
      }.values.toList

    case state: TorrentState.PiecesState =>
      piecesState = state
  }
}
