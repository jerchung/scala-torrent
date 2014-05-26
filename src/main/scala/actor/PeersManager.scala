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

  case class Unchoke(chosen: Set[ByteString], toChoke: Set[ByteString])
  case object OptimisticUnchoke
  case class Broadcast(message: Any)

}

/*
 * PeersManager takes care of the logic for choking / unchoking peers at set
 * intervals.  Keeps track of download rate of peers. Peers get connected /
 * disconnected based on messages from TorrentClient
 */
class PeersManager extends Actor { this: Parent with ScheduleProvider =>

  import context.dispatcher
  import PeersManager.PeerConnection
  import PeersManager.{ Unchoke, OptimisticUnchoke }

  val unchokeFrequency: FiniteDuration = 10 seconds
  val optimisticUnchokeFrequency: FiniteDuration = 30 seconds
  val numUnchokedPeers = 4

  // All currently connected Peers
  var peers = Map[ByteString, PeerConnection]()

  // Set of peerIds of currrent unchoked peers
  var currentUnchokedPeers = Set[ByteString]()

  override def preStart(): Unit = {
    scheduleUnchoke()
    scheduleOptimisticUnchoke()
  }

  def receive = {

    case PeerM.Connected(pid) =>
      peers += (pid -> PeerConnection(pid, sender, 0.0))

    case PeerM.Disconnected(pid, peerHas) =>
      peers -= pid
      currentUnchokedPeers -= pid

    case PeerM.Downloaded(pid, size) =>
      peers(pid).rate += size

    case PeerM.PieceDone(i) =>
      broadcast(BT.Have(i))

    case Unchoke(chosen, toChoke) =>
      chosen foreach { id => peers(id).peer ! BT.Unchoke }
      toChoke foreach { id => peers(id).peer ! BT.Choke }
      currentUnchokedPeers = chosen
      scheduleUnchoke()

    // TODO: Optimistic Unchoke
    case OptimisticUnchoke =>

  }

  def broadcast(message: Any): Unit = {
    peers foreach { case (pid, peerConn) =>
      peerConn.peer ! message
    }
  }

  def kMaxPeers(peers: Map[ByteString, PeerConnection], k: Int): Set[ByteString] = {
    val maxK = new mutable.PriorityQueue[PeerConnection]()
    var minPeerRate = 0.0;
    peers foreach { case (id, peer) =>
      if (maxK.size == k) {
        if (peer.rate > minPeerRate) {
          maxK.dequeue
          maxK.enqueue(peer)
          minPeerRate = maxK.head.rate
        }
      } else {
        maxK.enqueue(peer)

        // head actually returns the peerConnection with min rate due to the
        // inversion of priorities in the PeerConnection class
        minPeerRate = maxK.head.rate
      }

      // Reset rate for next choosing
      peer.rate = 0.0
    }
    maxK.foldLeft(Set[ByteString]()) { (peers, peerConn) =>
      peers + peerConn.id
    }
  }

  /*
   * Put in future for async
   */
  def scheduleUnchoke(): Unit = {
    scheduler.scheduleOnce(unchokeFrequency) {
      Future { unchoke(peers, currentUnchokedPeers, numUnchokedPeers) }
    }
  }

  def scheduleOptimisticUnchoke(): Unit = {
    scheduler.scheduleOnce(optimisticUnchokeFrequency) {
      self ! OptimisticUnchoke
    }
  }

  def unchoke(
      peers: Map[ByteString, PeerConnection],
      currentUnchoked: Set[ByteString],
      numUnchoked: Int): Unit = {
    val chosen = kMaxPeers(peers, numUnchoked)
    val toChoke = currentUnchoked &~ chosen
    self ! Unchoke(chosen, toChoke)
  }

}