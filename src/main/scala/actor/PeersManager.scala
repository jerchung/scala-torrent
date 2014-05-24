package org.jerchung.torrent.actor

import akka.actor.{ Actor, ActorRef, Props, Cancellable }
import akka.util.ByteString
import org.jerchung.torrent.Constant
import org.jerchung.torrent.actor.message.{ TorrentM, TrackerM, BT, PeerM }
import scala.annotation.tailrec
import scala.collection.mutable

object PeersManager {

  def props: Props = {
    Props(new PeersManager with ProdParent with ProdScheduler)
  }

  case class PeerConnection(id: ByteString, peer: ActorRef, var rate: Double)
      extends Ordered[PeerConnection] {

    // Want the minimum to be highest priority
    // When put into priority queue, max becomes min and vice versa
    def compare(that: PeerConnection): Int = {
      -1 * rate.compare(that.rate)
    }
  }

  sealed trait Message
  case object Unchoke extends Message
  case object OptimisticUnchoke extends Message
  case class Broadcast(message: Any) extends Message

}

/*
 * PeersManager takes care of the logic for choking / unchoking peers at set
 * intervals.  Keeps track of download rate of peers. Peers get connected /
 * disconnected based on messages from TorrentClient
 */
class PeersManager { this: Parent with ScheduleProvider =>

  import PeersManager.PeerConnection
  import PeersManager.{ Unchoke, OptimisticUnchoke }

  val unchokeFrequency: FiniteDuration = 10 seconds
  val optimisticUnchokeFrequency: FiniteDuration = 30 seconds
  val numUnchokedPeers = 4

  // All currently connected Peers
  var peers = Map.empty[ByteString, PeerConnection]

  // Set of peerIds of currrent unchoked peers
  var currentUnchokedPeers = Set.empty[ByteString]

  override def preStart(): Unit = {
    scheduleUnchoke
    scheduleOptimisticUnchoke
  }

  def receive = {

    case PeerM.Connected(pid) =>
      peers += (pid -> PeerConnection(pid, sender, 0.0))

    case PeerM.Disconnected(pid, peerHas) =>
      peers -= pid
      currentUnchokedPeers -= pid

    case PeerM.Downloaded(pid, size) =>
      peers(pid).rate += size

    case Unchoke =>
      val chosenPeers = kMaxPeers(numUnchokedPeers)
      val peersToChoke = currentUnchokedPeers &~ chosenPeers
      chosenPeers foreach { id => peers(id).peer ! BT.Unchoke }
      peersToChoke foreach { id => peers(id).peer ! BT.Choke }
      currentUnchokedPeers = chosenPeers
      scheduleUnchoke

    // TODO: Optimistic Unchoke
    case OptimisticUnchoke =>

  }

  // Find top k peers based on upload speed
  def kMaxPeers(k: Int): Set[ByteString] = {
    val maxK = new mutable.PriorityQueue[PeerConnection]()
      var minPeerRate = 0.0;
      peers foreach { case (id, peer) =>
        if (maxK.size == k) {
          if (peer.rate > minPeerRate) {
            maxK.dequeue
            maxK.enqueue(peer)
            minPeerRate = maxK.max.rate
          }
        } else {
          maxK.enqueue(peer)

          // Max actually returns the peerConnection with min rate due to the
          // inversion of priorities in the PeerConnection class
          minPeerRate = maxK.max.rate
        }

        // Reset rate for next choosing
        peer.rate = 0.0
      }
      maxK.foldLeft(Set[ByteString]()) { (peers, peerConn) =>
        peerConn.id + peers
      }
    }
  }

  def scheduleUnchoke: Unit = {
    scheduler.scheduleOnce(unchokeFrequency) {
      self ! Unchoke
    }
  }

  def scheduleOptimisticUnchoke: Unit = {
    scheduler.scheduleOnce(optimisticUnchokeFrequency) {
      self ! OptimisticUnchoke
    }
  }

}