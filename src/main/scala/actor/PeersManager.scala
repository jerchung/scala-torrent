package org.jerchung.torrent.actor

import akka.actor.{ Actor, ActorRef, Props, Cancellable }
import akka.util.ByteString
import org.jerchung.torrent.Constant
import org.jerchung.torrent.actor.message.{ TrackerM, BT, PeerM }
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

object PeersManager {

  def props: Props = {
    Props(new PeersManager)
  }

  implicit val ordering = new Ordering[(ActorRef, Float)] {
    def compare(x: (ActorRef, Float), y: (ActorRef, Float)) = y._2 compare x._2
  }

  case class PeerConnection(id: ByteString, peer: ActorRef, var rate: Double)
      extends Ordered[PeerConnection] {

    // Want to invert priority
    // When put into priority queue, max becomes min and vice versa
    def compare(that: PeerConnection): Int = {
      -1 * rate.compare(that.rate)
    }
  }

  case object Unchoke
  case class UnchokeChosen(chosen: Set[ActorRef])
  case object OptimisticUnchoke
  case class OptimisticUnchokeChosen(chosen: ActorRef)
  case class OldPeer(peer: ActorRef)
  case class Broadcast(message: Any)

}

/*
 * PeersManager takes care of the logic for choking / unchoking peers at set
 * intervals.  Keeps track of download rate of peers. Peers get connected /
 * disconnected based on messages from TorrentClient
 */
class PeersManager extends Actor {

  import context.dispatcher
  import context.system
  import PeersManager._

  val unchokeFrequency: FiniteDuration = 10 seconds
  val optimisticUnchokeFrequency: FiniteDuration = 30 seconds
  val newlyConnectedDuration: FiniteDuration = 1 minute
  val numUnchokedPeers = 4

  // All currently connected Peers (actorRef -> Download Rate)
  // Need to differentiate between newly connected peers and old peers for unchoking
  // purposes
  var peerRates = Map[ActorRef, Float]()
  var newPeers = Set[ActorRef]()

  var interestedPeers = Set[ActorRef]()

  // Set of peerIds of currrent unchoked peers
  var currentUnchokedPeers = Set[ActorRef]()

  override def preStart(): Unit = {
    scheduleUnchoke()
    scheduleOptimisticUnchoke()
  }

  def receive = {

    case PeerM.Connected =>
      val peer = sender
      newPeers += peer
      peerRates += (peer -> 0f)
      system.scheduler.scheduleOnce(newlyConnectedDuration) {
        newPeers -= peer
      }

    case PeerM.Disconnected(peerHas) =>
      newPeers -= sender
      peerRates -= sender
      currentUnchokedPeers -= sender

    case PeerM.Downloaded(size) =>
      peerRates += (sender -> (peerRates(sender) + size.toFloat))

    case PeerM.PieceDone(i) =>
      broadcast(BT.Have(i))

    case BT.NotInterestedR =>
      interestedPeers -= sender

    case BT.InterestedR =>
      interestedPeers += sender

    case Unchoke =>
      val chosen = kMaxPeers(peerRates, numUnchokedPeers)
      self ! UnchokeChosen(chosen)

    case UnchokeChosen(chosen) =>
      val toChoke = currentUnchokedPeers &~ chosen
      chosen foreach { _ ! BT.Unchoke }
      toChoke foreach { _ ! BT.Choke }
      currentUnchokedPeers = chosen
      scheduleUnchoke()

    // Newly connected peers are 3 times as likely to be unchoked
    case OptimisticUnchoke =>
      optimisticChoosePeer foreach { self ! OptimisticUnchokeChosen(_) }

    // Choke worst performing peer out of currently unchoked peers and unchoke
    // the chosen peer
    case OptimisticUnchokeChosen(peer) =>
      if (currentUnchokedPeers.size >= numUnchokedPeers) {
        val minPeer = currentUnchokedPeers minBy { peerRates }
        minPeer ! BT.Choke
        currentUnchokedPeers -= minPeer
      }
      peer ! BT.Unchoke
      currentUnchokedPeers += peer

  }

  def broadcast(message: Any): Unit = peerRates.keys foreach { _ ! message }

  def optimisticChoosePeer(): Option[ActorRef] = {
    val interestedNewPeers = newPeers & interestedPeers
    val otherInterestedPeers = interestedPeers &~ interestedNewPeers
    val numInterested = 3 * interestedNewPeers.size + otherInterestedPeers.size
    if (numInterested == 0) {
      None
    } else {
      val baseProb = 1 / numInterested.toFloat
      val newPeerProb = 3 * baseProb
      val peerProbabilities: Vector[(ActorRef, Float)] =
        interestedNewPeers.toVector.map((peer: ActorRef) => (peer, newPeerProb)) ++
        otherInterestedPeers.toVector.map((peer: ActorRef) => (peer, baseProb))
      Some(weightedSelect(peerProbabilities))
    }
  }

  def weightedSelect(peerProbabilities: Vector[(ActorRef, Float)]): ActorRef = {

    @tailrec
    def selectHelper(idx: Int, cutoff: Float): ActorRef = {
      if (cutoff < peerProbabilities(idx)._2)
        peerProbabilities(idx)._1
      else
        selectHelper(idx + 1, cutoff - peerProbabilities(idx)._2)
    }

    selectHelper(0, Random.nextFloat)
  }

  def kMaxPeers(peers: Map[ActorRef, Float], k: Int): Set[ActorRef] = {
    // Use implicit ordering in the PeersManager companion object
    val maxKPeers = new mutable.PriorityQueue[(ActorRef, Float)]()
    var minPeerRate = 0f;
    peers foreach { case (peer, rate) =>
      if (maxKPeers.size == k) {
        if (rate > minPeerRate) {
          maxKPeers.dequeue
          maxKPeers.enqueue((peer, rate))
          minPeerRate = maxKPeers.head._2
        }
      } else {
        maxKPeers.enqueue((peer, rate))
        minPeerRate = maxKPeers.head._2
      }
      // Reset download rate for next unchoke
      peerRates += (peer -> 0f)
    }
    maxKPeers.foldLeft(Set[ActorRef]()) {
      case (peers, (peer, rate)) =>
        peers + peer
    }
  }

  def scheduleUnchoke(): Unit = {
    system.scheduler.scheduleOnce(unchokeFrequency) {
      self ! Unchoke
    }
  }

  def scheduleOptimisticUnchoke(): Unit = {
    system.scheduler.scheduleOnce(optimisticUnchokeFrequency) {
      self ! OptimisticUnchoke
    }
  }

}