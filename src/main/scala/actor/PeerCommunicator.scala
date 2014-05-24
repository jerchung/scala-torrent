package org.jerchung.torrent.actor

import akka.actor.{ Actor, ActorRef, Props, Cancellable }
import org.jerchung.torrent.actor.message. { PeerM, FM }

object PeerCommunicator {
  def props(
      fileManager: ActorRef,
      peersManager: ActorRef,
      piecesTracker: ActorRef): Props = {
    Props(new PeerCommunicator(fileManager, peersManager, piecesTracker))
  }
}

/*
 * Acts as a forwarding actor which will send messages originating from peers
 * to the correct actor for the correct action.  Messages will be forwarded to
 * preserve the sender reference
 */
class PeerCommunicator(
    fileManager: ActorRef,
    peersManager: ActorRef,
    piecesTracker: ActorRef)
    extends Actor {

  def receive = {

    // Messages to be sent to both peersManager and piecesTracker
    case msg @ (_: PeerM.Disconnected | _: PeerM.Connected) =>
      peersManager forward msg
      piecesTracker forward msg

    // peersManager only
    case msg @ (_: PeerM.Downloaded) =>
      peersManager forward msg

    // PiecesTracker only
    case msg @ (_: PeerM.Resume | _: PeerM.Ready | _:PeerM.ChokedOnPiece |
                _: PeerM.PieceAvailable | _: PeerM.PieceDone) =>
      piecesTracker forward msg

    // fileManager only
    case msg @ (_: FM.Write | _: FM.Read) =>
      fileManager forward msg

  }


}