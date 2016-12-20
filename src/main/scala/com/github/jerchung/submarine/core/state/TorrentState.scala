package com.github.jerchung.submarine.core.state

import akka.actor.{Actor, Props}
import akka.event.EventStream
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.Torrent
import com.github.jerchung.submarine.core.peer.Peer

object TorrentState {
  def props(args: Args): Props = {
    Props(new TorrentState(args))
  }

  trait Relevant

  case class Args(torrentId: Int,
                  torrent: Torrent,
                  torrentEvents: EventStream)

  object Peer {
    case class Metadata(id: ByteString,
                        ip: String,
                        port: Int,
                        )
  }
}

class TorrentState(args: TorrentState.Args) extends Actor {

  var peers: Map

  override def preStart(): Unit = {
    args.torrentEvents.subscribe(self, classOf[TorrentState.Relevant])
  }

  def receive: Receive = {
    case Peer.Announce(_, publish) =>
      publish match {
        case Peer.Connected(args) =>

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
