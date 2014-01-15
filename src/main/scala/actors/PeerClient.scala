package org.jerchung.torrent

import ActorMessage.{ PeerM, BT }
import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import scala.concurrent.duration._

object PeerClient {
  def props(peer: Peer, ownAvailable: Array[Byte], protocol: ActorRef) = {
    Props(classOf[PeerClient], peer, ownAvailable, infoHash)
  }
}

// One of these actors per peer
/* Parent must be Torrent Client */
class PeerClient(peer: Peer, protocol: ActorRef) extends Actor {

  import context.{ system, become, parent, dispatcher }

  val peerId = peer.peerId
  val ownId = peer.ownId
  val infoHash = peer.infoHash
  val ownAvailable = peer.ownAvailable
  val peerAvailable = Array.fill[Byte](numPieces)(0)

  // Need to keep mutable state
  var keepAlive = false
  var immediatePostHandshake = true
  var amChoking, peerChoking = true
  var amInterested, peerInterested = false

  def receive: Receive = {
    case PeerM.Handshake => protocol ! BT.Handshake(infoHash, ownId)
    case m: BT.Message => protocol ! m
    case r: BT.Reply => handleReply(r)
  }

  def handleReply(reply: BT.Reply) = {
    reply match {
      case BT.KeepAliveR => keepAlive = true
      case BT.ChokeR => peerChoking = true
      case BT.UnchokeR => peerChoking = false
      case BT.InterestedR => peerInterested = true
      case BT.NotInterestedR => peerInterested = false
      case u: BT.Update => updatePieces(u)
      case BT.RequestR(index, begin, length) =>
      case BT.HandshakeR(infoHash, peerId) =>
        if (infoHash != this.infoHash || peerId != this.peerId) {
          parent ! "Invalid"
          context stop self
        } else {

        }

    }
    immediatePostHandshake = false
  }

  def updatePieces(update: BT.Update): Unit = {
    update match {
      case BT.BitfieldR(bitfield) => peerAvailable = bitfield.toArray
      case BT.HaveR(index) => peerAvailable(index) = 1
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