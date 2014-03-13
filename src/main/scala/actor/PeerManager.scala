package org.jerchung.torrent.actor

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.ByteString
import org.jerchung.torrent.actor.message.{ BT, PM }
import org.jerchung.torrent.Constant
import scala.collection.mutable
import scala.concurrent.duration._

object PeerManager {

  def props: Props = {
    Props(new PeerManager with ProdParent with ProdScheduler)
  }

  case class PeerConnection (id: ByteString, peer: ActorRef, var rate: Double)
      extends Ordered[PeerConnection] {

    // Want the minimum to be highest priority
    // When put into priority queue, max becomes min and vice versa
    def compare(that: PeerConnection): Int = {
      -1 * rate.compare(that.rate)
    }
  }

}

class PeerManager extends Actor { this: Parent with ScheduleProvider =>

  import PeerManager.PeerConnection
  import context.dispatcher

  override def preStart(): Unit = {

  }

  val unchokeFrequency: FiniteDuration = 10 seconds

  val connectedPeers = mutable.Map[ByteString, PeerConnection]()
  var unchokedPeers = Map[ByteString, ActorRef]()

  def receive = {
    case PM.Register(peerId) => connectedPeers(peerId) = PeerConnection(peerId, sender, 0.0)
    case PM.Disconnected(peerId) => connectedPeers -= peerId
    case msg: BT.Message => broadcast(msg)
  }

  def broadcast(message: BT.Message): Unit = {

  }

  // Find top K contributors and unchoke those, while choking everyone else
  def getMaxPeers: Map[ByteString, ActorRef] = {
    val maxK = new mutable.PriorityQueue[PeerConnection]()
    connectedPeers foreach { case (id, peer) =>
      if (maxK.size == Constant.NumUnchokedPeers) {
        // Max actually returns the peerConnection with min rate due to the
        // inversion of priorities
        val minPeerRate = maxK.max.rate
        if (peer.rate > minPeerRate) {
          maxK.dequeue
          maxK.enqueue(peer)
        }
      } else {
        maxK.enqueue(peer)
      }
    }
    maxK.foldLeft(Map[ByteString, ActorRef]()) { (peers, peerConn) =>
      peers + (peerConn.id -> peerConn.peer)
    }
  }

  def unchokePeers: Unit = {
    unchokedPeers = getMaxPeers
    scheduler.scheduleOnce(unchokeFrequency) { unchokePeers }
  }

}