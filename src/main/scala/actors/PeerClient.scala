package org.jerchung.torrent

import ActorMessage.{ PeerM, BT }
import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import scala.concurrent.duration._

object PeerClient {
  def props(peerId: ByteString, infoHash: ByteString, protocol: ActorRef) = {
    Props(classOf[PeerClient], peerId, infoHash)
  }
}

// One of these actors per peer
/* Parent must be Torrent Client */
class PeerClient(
    peerId: ByteString,
    infoHash: ByteString,
    protocol: ActorRef) extends Actor {

  import context.{ system, become, parent, dispatcher }

  // Need to keep state
  var keepAlive = false
  var immediatePostHandshake = true
  var amChoking, peerChoking = true
  var amInterested, peerInterested = false
  val availablePieces = Array.fill[Byte](numPieces)(0)

  /*def receive = {
    case BT.Protocol(actor) =>
      protocol = actor
      become(connected)
      sender ! true
  }*/

  def receive: Receive = {
    case PeerM.Handshake => protocol ! BT.Handshake(infoHash, peerId)
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
      case u: BT.Update => updateAvailable(u)
      case BT.RequestR(index, begin, length) =>
      case BT.HandshakeR(peerHash, otherPeerId) =>
        if (replyingHandshake) {  }
    }
    immediatePostHandshake = false
  }

  def updateAvailable(update: BT.Update): Unit = {
    update match {
      case BT.BitfieldR(bitfield) => availablePieces = bitfield.toArray
      case BT.HaveR(index) => availablePieces(index) = 1
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