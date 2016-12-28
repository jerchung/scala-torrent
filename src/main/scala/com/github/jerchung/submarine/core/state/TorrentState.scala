package com.github.jerchung.submarine.core.state

import akka.actor.{Actor, ActorRef, Props}
import akka.event.EventStream
import com.github.jerchung.submarine.core.base.{Core, Torrent}
import com.github.jerchung.submarine.core.peer.Peer
import com.github.jerchung.submarine.core.implicits.Convert._
import com.github.jerchung.submarine.core.piece.PiecePipeline
import com.github.jerchung.submarine.core.state.TorrentState.Aggregated

object TorrentState {
  def props(args: Args): Props = {
    Props(new TorrentState(args))
  }

  trait Provider {
    def torrentState(args: Args): ActorRef
  }

  trait AppProvider extends Provider { this: Core.AppProvider =>
    override def torrentState(args: Args): ActorRef =
      context.actorOf(TorrentState.props(args))
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

  case class Aggregated(totalDownloaded: Int,
                        totalUploaded: Int,
                        totalSize: Int)

  sealed trait Request
  object Request {
    case object CurrentState extends Request
  }

  sealed trait Response
  object Response {
    case class State(aggregated: Aggregated)
  }
}

class TorrentState(args: TorrentState.Args) extends Actor {

  var peers: Map[ActorRef, TorrentState.Peer.Metadata] = Map()

  var aggregated: TorrentState.Aggregated = Aggregated(0, 0, args.torrent.totalSize)

  override def preStart(): Unit = {
    args.torrentEvents.subscribe(self, classOf[TorrentState.Relevant])
  }

  def receive: Receive = {
    case Peer.Announce(peer, publish) =>
      publish match {
        case Peer.Connected(peerArgs) =>
          val (peerId, ip, port) = (peerArgs.peerId.toChars, peerArgs.ip, peerArgs.port)
          val peerMetadata = TorrentState.Peer.Metadata(peerId, ip, port)

          peers += (peer -> peerMetadata)

        case _: Peer.Disconnected =>
          peers -= peer

        case Peer.Uploaded(bytes) =>
          aggregated = aggregated.copy(
            totalUploaded = aggregated.totalUploaded + bytes
          )

        case _ => ()
      }

    case PiecePipeline.Done(_, piece) =>
      aggregated = aggregated.copy(
        totalDownloaded = aggregated.totalDownloaded + piece.length
      )

    case TorrentState.Request.CurrentState =>
      sender ! TorrentState.Response.State(aggregated = aggregated)
  }
}
