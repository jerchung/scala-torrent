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

  case object Unchoke
  case object OptimisticUnchoke
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
  val NumUnchoked = 4

  // All currently connected Peers (actorRef -> Download Rate)
  // Need to differentiate between newly connected peers and old peers for unchoking
  // purposes
  var peerRates = Map[ActorRef, Float]()
  var newPeers = Set[ActorRef]()
  var interestedPeers = Set[ActorRef]()
  var currentUnchoked = Set[ActorRef]()

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
        self ! OldPeer(peer)
      }

    case PeerM.Disconnected(peerHas) =>
      newPeers -= sender
      peerRates -= sender
      currentUnchoked -= sender

    case PeerM.Downloaded(size) if (peerRates contains sender) =>
      peerRates += (sender -> (peerRates(sender) + size.toFloat))

    case PeerM.PieceDone(i) =>
      broadcast(BT.Have(i))

    case BT.NotInterestedR =>
      interestedPeers -= sender

    case BT.InterestedR =>
      interestedPeers += sender

    case OldPeer(peer) =>
      newPeers -= peer

    // Unchoke top NumUnchoked peers
    case Unchoke =>
      val topKTuples = peerRates.toList.sortBy(- _._2).take(NumUnchoked)
      val chosen = topKTuples.map(_._1).toSet
      val toChoke = currentUnchoked &~ chosen
      chosen foreach { _ ! BT.Unchoke }
      toChoke foreach { _ ! BT.Choke }
      currentUnchoked = chosen
      peerRates = peerRates mapValues { _ => 0f }
      scheduleUnchoke()

    // Newly connected peers are 3 times as likely to be unchoked
    // Choke worst performing peer out of currently unchoked peers and unchoke
    // the chosen peer
    case OptimisticUnchoke =>
      optimisticChoosePeer foreach { peer =>
        if (currentUnchoked.size >= NumUnchoked &&
            !currentUnchoked.contains(peer)) {
          val minPeer = currentUnchoked minBy { peerRates }
          minPeer ! BT.Choke
          currentUnchoked -= minPeer
        }
        peer ! BT.Unchoke
        currentUnchoked += peer
      }
      scheduleOptimisticUnchoke()
  }

  def broadcast(message: Any): Unit = peerRates.keys foreach { _ ! message }

  def optimisticChoosePeer(): Option[ActorRef] = {
    val interestedNewPeers = newPeers & interestedPeers
    val interestedOldPeers = interestedPeers &~ interestedNewPeers
    val numInterested = 3 * interestedNewPeers.size + interestedOldPeers.size
    if (numInterested == 0) {
      None
    } else {
      val baseProb = 1 / numInterested.toFloat
      val newPeerProb = 3 * baseProb
      val peerProbabilities: Vector[(ActorRef, Float)] =
        interestedNewPeers.toVector.map((peer: ActorRef) => (peer, newPeerProb)) ++
        interestedOldPeers.toVector.map((peer: ActorRef) => (peer, baseProb))
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

    try {
      selectHelper(0, Random.nextFloat)
    } catch {
      case e: Throwable =>
        peerProbabilities.head._1
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