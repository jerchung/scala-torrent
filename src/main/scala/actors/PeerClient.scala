package org.jerchung.torrent

import ActorMessage.{ PeerM, BT }
import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import java.net.InetSocketAddress
import scala.concurrent.duration._

object PeerClient {
  def props(peerId: ByteString, infoHash: ByteString,
      remote: Option[InetSocketAddress] = None, connection: Option[ActorRef] = None) = {
    Props(classOf[PeerClient], infoHash, connection, remote, connection)
  }
}

// One of these actors per peer
class PeerClient(
    peerId: ByteString,
    infoHash: ByteString,
    remote: Option[InetSocketAddress],
    connection: Option[ActorRef]) extends Actor {

  import context.{ system, become, parent, dispatcher }

  // Need to keep state
  var keepAlive = false
  var immediatePostHandshake = true
  var amChoking, peerChoking = true
  var amInterested, peerInterested = false
  val availablePieces = Array.fill[Byte](numPieces)(0)
  var protocol: ActorRef = connection match {
    case Some(c) => c
    case None => null.asInstanceOf[ActorRef]
  }

  remote match {
    case Some(a) => IO(Tcp) ! Tcp.Connect(a)
    case None => become(connected)
  }

  def receive = {
    case Tcp.CommandFailed(_: Tcp.Connect) =>
      parent ! "Failed to connect"
      context stop self
    case Tcp.Connected(remote, local) =>
      protocol = context.actorOf(TorrentProtocol.props(sender, self))
      parent ! PeerM.Connected
      protocol ! BT.Handshake(infoHash, peerId)
      become(connected)
  }

  def connected: Receive = {
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