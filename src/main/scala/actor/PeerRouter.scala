package org.jerchung.torrent.actor

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import org.jerchung.torrent.actor.message.FM
import org.jerchung.torrent.actor.message.PeerM
import org.jerchung.torrent.actor.message.BT

object PeerRouter {
  def props(
      fileManager: ActorRef,
      peersManager: ActorRef,
      piecesManager: ActorRef): Props = {
    Props(new PeerRouter(fileManager, peersManager, piecesManager))
  }
}

/*
 * Acts as a forwarding actor which will send messages originating from peers
 * to the correct actor for the correct action.  Messages will be forwarded to
 * preserve the sender reference
 */
class PeerRouter(
    fileManager: ActorRef,
    peersManager: ActorRef,
    piecesManager: ActorRef)
  extends Actor {

  def receive = {

    // Messages to be sent to both peersManager and piecesManager
    case msg @ (_: PeerM.Disconnected | PeerM.Connected) =>
      peersManager forward msg
      piecesManager forward msg

    // peersManager only
    case msg @ (_: PeerM.Downloaded | BT.NotInterestedR | BT.InterestedR) =>
      peersManager forward msg

    // PiecesManager only
    case msg @ (PeerM.Resume |
                _: PeerM.ReadyForPiece |
                _: PeerM.ChokedOnPiece |
                _: PeerM.PieceAvailable |
                _: PeerM.PieceDone |
                _: PeerM.PieceInvalid) =>
      piecesManager forward msg

    // fileManager only
    case msg @ (_: FM.Write | _: FM.Read) =>
      fileManager forward msg

  }


}