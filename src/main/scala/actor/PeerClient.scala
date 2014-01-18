package org.jerchung.torrent.actor

import ActorMessage.{ PeerM, BT }
import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import scala.collection.immutable.BitSet
import scala.collection.mutable
import scala.concurrent.duration._

object PeerClient {
  def props(peer: Peer, protocol: ActorRef, blockManager: ActorRef, replying: Boolean) = {
    Props(classOf[PeerClient], peer, protocol, blockManager, replying)
  }
}

// One of these actors per peer
/* Parent must be Torrent Client */
class PeerClient(info: Peer, protocol: ActorRef, blockManager: ActorRef replying: Boolean) extends Actor {

  import context.{ system, become, parent, dispatcher }

  val peerId: ByteString   = info.peerId
  val ownId: ByteString    = info.ownId
  val infoHash: ByteString = info.infoHash
  var iHave: BitSet        = info.ownAvailable
  var peerHas: BitSet      = BitSet.empty

  // Need to keep mutable state
  var keepAlive                    = false
  var immediatePostHandshake       = true
  var amChoking, peerChoking       = true
  var amInterested, peerInterested = false

  override def preStart(): Unit = {
    val listenerSet = (protocol ? BT.Listener(self)).mapTo[Boolean]
    val listenerSet onSuccess {
      case true if (replying) => protocol ! BT.Handshake(infoHash, ownId)
    }
  }

  override def postStop(): Unit = {
    parent ! Unavailable(peerHas)
  }

  def receive = {
    case PeerM.Handshake => protocol ! BT.Handshake(infoHash, ownId)
    case m: BT.Message   =>
      m match {
        case BT.Have(index)        => iHave += index
        case BT.Bitfield(bitfield) => iHave = bitfield
        case _                     =>
      }
      protocol ! m
    case r: BT.Reply     => handleReply(r)
  }

  def handleReply(reply: BT.Reply): Unit = {
    reply match {
      case BT.KeepAliveR => keepAlive = true
      case BT.ChokeR => peerChoking = true
      case BT.UnchokeR => peerChoking = false
      case BT.InterestedR => peerInterested = true
      case BT.NotInterestedR => peerInterested = false
      case update: BT.UpdateR => updatePeerAvailable(msg)
      case BT.RequestR(index, offset, length) =>
      case BT.PieceR(index, offset, block) => blockManager ! Write(index, offset, block)
      case BT.HandshakeR(infoHash, peerId) =>
        if (infoHash != this.infoHash || peerId != this.peerId) {
          parent ! "Invalid"
          context stop self
        } else {

        }
    }
    immediatePostHandshake = false
  }

  def updatePeerAvailable(msg: BT.UpdateR): Unit = {
    msg match {
      case BT.BitfieldR(bitfield) =>
        peerHas |= BitSet.fromBitMask(Array(bitfield))
        parent ! Available(Right(peerHas))
      case BT.HaveR(index) =>
        peerHas |= BitSet(index)
        parent ! Available(Left(index))
    }
  }

  // Start off the scheduler to send keep-alive signals every 2 minutes and to
  // check that keep-alive is being sent to itself from the peer
  def heartbeat: Unit = {
    checkHeartbeat
    system.scheduler.schedule(0 millis, 1.5 minutes) { protocol ! BT.KeepAlive }
  }

  // Check if keep-alive is sent from peer
  def checkHeartbeat: Unit = {
    if (keepAlive) {
      keepAlive = false
      system.scheduler.scheduleOnce(3 minutes) { checkHeartbeat }
    } else {
      parent ! "No heartbeat"
      context stop self
    }
  }

}