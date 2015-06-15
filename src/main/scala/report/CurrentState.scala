package storrent.report

import akka.actor.{Actor, ActorRef, Props}
import akka.util.ByteString
import storrent.message._
import scala.collection.mutable
import scala.collection.BitSet
import storrent.core.{PiecesManager, PeersManager}
import storrent.peer._

object CurrentState {
  def props(): Props = {
    Props(new CurrentState)
  }

  case object GetState
  case class State(
    peerInfos: Map[ActorRef, PeerInfo],
    peerRates: Map[ActorRef, Float],
    seeds: Set[ActorRef],
    pieceCounts: Map[Int, Int],
    completedPieces: BitSet
  )
}

class CurrentState extends Actor {
  import CurrentState._

  var peerInfos = Map[ActorRef, PeerInfo]()
  var peerRates = Map[ActorRef, Float]()
  var seeds = Set[ActorRef]()
  var pieceCounts = Map[Int, Int]()
  var completedPieces = BitSet()

  def receive = {

    case PeersManager.StateUpdate(peerInfos, peerRates, seeds) =>
      this.peerInfos = peerInfos
      this.peerRates = peerRates
      this.seeds = seeds

    case PiecesManager.StateUpdate(pieceCounts, completedPieces) =>
      this.pieceCounts = pieceCounts
      this.completedPieces = completedPieces

    case GetState =>
      sender ! State(peerInfos, peerRates, seeds, pieceCounts, completedPieces)
  }
}
