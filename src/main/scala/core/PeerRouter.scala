package storrent.core

import akka.actor.{ Actor, ActorRef, Props, Cancellable }
import storrent.message. { PeerM, FM, BT }

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
    case msg @ (_: PeerM.Disconnected |
                _: PeerM.Connected |
                _: PeerM.PieceDone) =>
      peersManager forward msg
      piecesManager forward msg

    // peersManager only
    case msg @ (_: PeerM.Downloaded |
                BT.InterestedR |
                BT.NotInterestedR |
                PeerM.IsSeed) =>
      peersManager forward msg

    // PiecesManager only
    case msg @ (_: PeerM.Resume |
                _: PeerM.ReadyForPiece |
                _: PeerM.ChokedOnPiece |
                _: PeerM.PieceAvailable |
                _: PeerM.PieceInvalid) =>
      piecesManager forward msg

    // fileManager only
    case msg @ (_: FM.Write | _: FM.Read) =>
      fileManager forward msg

  }


}
