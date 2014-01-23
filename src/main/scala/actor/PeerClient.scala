package org.jerchung.torrent.actor

import ActorMessage.{ PeerM, BT }
import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import akka.util.Timeout
import org.jerchung.torrent.actor.PeerClient.{ State, ReplyingHandshake }
import scala.collection.immutable.BitSet
import scala.collection.mutable
import scala.concurrent.duration._

object PeerClient {

  sealed trait State
  case object ReplyingHandshake extends State

  def props(peer: Peer, protocol: ActorRef, fileManager: ActorRef, states: State*) = {
    Props(classOf[PeerClient], peer, protocol, fileManager, states)
  }
}

// One of these actors per peer
/* Parent must be Torrent Client */
class PeerClient(info: Peer, protocol: ActorRef, fileManager: ActorRef, states: State*) extends Actor {

  import context.{ system, become, parent, dispatcher }

  implicit val timeout = Timeout(5 millis)

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
    for {
      listenerSet <- (protocol ? BT.Listener(self)).mapTo[Boolean]
      state <- states
    } yield {
      state match {
        case ReplyingHandshake => protocol ! BT.Handshake(infoHash, ownId)
        case _ =>
      }
    }
  }

  // Notify TorrentClient of disconnecting peer with info needed update piece
  // frequency counts etc.
  override def postStop(): Unit = {
    parent ! DisconnectedPeer(peerId, peerHas)
  }

  def receive = {
    case PeerM.Handshake => protocol ! BT.Handshake(infoHash, ownId)
    case m: BT.Message   => handleMessage(m)
    case r: BT.Reply     => handleReply(r)
  }

  /*
  * Update state according to message and then send message along to protocol
  * to be send over the wire to the peer in ByteString form
  */
  def handleMessage(message: BT.Message): Unit = {
    message match {
      case BT.Choke              => amChoking = true
      case BT.Unchoke            => amChoking = false
      case BT.Interested         => amInterested = true
      case BT.NotInterested      => amInterested = false
      case BT.Have(index)        => iHave += index
      case BT.Bitfield(bitfield) => iHave = bitfield
      case _                     =>
    }
    protocol ! message
  }

  def handleReply(reply: BT.Reply): Unit = {
    reply match {
      case BT.KeepAliveR                      => keepAlive = true
      case BT.ChokeR                          => peerChoking = true
      case BT.UnchokeR                        => peerChoking = false
      case BT.InterestedR                     => peerInterested = true
      case BT.NotInterestedR                  => peerInterested = false
      case update: BT.UpdateR                 => updatePeerAvailable(msg)
      case BT.RequestR(index, offset, length) =>
      case BT.PieceR(index, offset, block)    => fileManager ! Write(index, offset, block)
      case BT.CancelR(index, offset, length)  =>
      case BT.HandshakeR(infoHash, peerId)    =>
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
        peerHas |= bitfield
        parent ! Available(Right(peerHas))
      case BT.HaveR(index) =>
        peerHas += index
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