package org.jerchung.torrent.actor

import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import akka.util.Timeout
import org.jerchung.torrent.actor.message.{ PeerM, BT, TorrentM, FM }
import scala.collection.BitSet
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

object Peer {
  def props(info: PeerInfo, protocolProps: Props, fileManager: ActorRef): Props = {
    Props(new Peer(info, protocolProps, fileManager) with ProdParent)
  }
}

// One of these actors per peer
/* Parent must be Torrent Client */
class Peer(info: PeerInfo, protocolProps: Props, fileManager: ActorRef)
    extends Actor { this: Parent =>

  import context.{ system, dispatcher }

  implicit val timeout = Timeout(5 millis)

  val protocol = context.actorOf(protocolProps)

  var peerId: Option[ByteString] = info.peerId
  val ip: String                 = info.ip
  val port: Int                  = info.port
  val ownId: ByteString          = info.ownId
  val infoHash: ByteString       = info.infoHash
  var iHave: BitSet              = BitSet.empty
  var peerHas: BitSet            = BitSet.empty

  // Need to keep mutable state
  var keepAlive                    = true
  var amChoking, peerChoking       = true
  var amInterested, peerInterested = false

  // Depending on if peerId is None or Some, then that dictates whether this
  // actor initiates a handshake with a peer, or waits for the peer to send a
  // handshake over
  override def preStart(): Unit = {
    peerId match {
      case Some(id) =>
        protocol ! BT.Handshake(infoHash, id)
        context.become(initiatedHandshake)
      case None =>
        context.become(waitingForHandshake)
    }
  }

  override def postStop(): Unit = {
    peerId map { id => parent ! TorrentM.DisconnectedPeer(id, peerHas) }
  }

  def waitingForHandshake: Receive = {
    case BT.HandshakeR(infoHash, peerId) if (infoHash == this.infoHash) =>
      protocol ! BT.Handshake(infoHash, ownId)
      parent ! TorrentM.Register(peerId)
      this.peerId = Some(peerId)
      heartbeat
      context.become(acceptBitfield)
    case _ => context stop self
  }

  def initiatedHandshake: Receive = {
    case BT.Handshake(infoHash, peerId)
        if (infoHash == this.infoHash && peerId == this.peerId.get) =>
      parent ! TorrentM.Register(peerId)
      heartbeat
      context.become(acceptBitfield)
    case _ => context stop self
  }

  /**
   * Bitfield must be first message sent to you from peer for it to be valid
   */
  def acceptBitfield: Receive = {
    case BT.BitfieldR(bitfield) =>
      peerHas |= bitfield
      parent ! TorrentM.Available(Right(peerHas))
      context.become(receive)
    case msg =>
      receive(msg)
      context.become(receive)
  }

  def receive = {
    case m: BT.Message => handleMessage(m)
    case r: BT.Reply => handleReply(r)
  }

  /*
  * Update state according to message and then send message along to protocol
  * to be send over the wire to the peer in ByteString form
  */
  def handleMessage(message: BT.Message): Unit = {
    message match {
      case BT.Choke                         => amChoking = true
      case BT.Unchoke                       => amChoking = false
      case BT.Interested                    => amInterested = true
      case BT.NotInterested                 => amInterested = false
      case BT.Have(index)                   => iHave += index
      case BT.Bitfield(bitfield, numPieces) => iHave = bitfield
      case _                                =>
    }
    protocol ! message
  }

  def handleReply(reply: BT.Reply): Unit = {
    reply match {
      case BT.KeepAliveR                  => keepAlive = true
      case BT.ChokeR                      => peerChoking = true
      case BT.UnchokeR                    => peerChoking = false
      case BT.InterestedR                 => peerInterested = true
      case BT.NotInterestedR              => peerInterested = false
      case b @ BT.RequestR(idx, off, len) => if (iHave contains idx) { parent ! b }
      case BT.PieceR(idx, off, block)     => fileManager ! FM.Write(idx, off, block)
      case BT.CancelR(idx, off, len)      =>
      case BT.HaveR(idx) =>
        peerHas += idx
        parent ! TorrentM.Available(Left(idx))
      case _ =>
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