package org.jerchung.torrent.actor

import org.jerchung.torrent.actor.message.{ PeerM, BT, TorrentM, FM }
import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import akka.util.Timeout
import scala.collection.BitSet
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

object Peer {
  def props(info: PeerInfo, connection: ActorRef, fileManager: ActorRef): Props = {
    Props(classOf[Peer], info, connection, fileManager)
  }
}

// One of these actors per peer
/* Parent must be Torrent Client */
class Peer(info: PeerInfo, connection: ActorRef, fileManager: ActorRef) extends Actor {

  import context.{ system, become, parent, dispatcher }

  implicit val timeout = Timeout(5 millis)

  val protocol = context.actorOf(TorrentProtocol.props(connection))

  var peerId: Option[ByteString] = info.peerId
  val ip: String                 = info.ip
  val port: Int                  = info.port
  val ownId: ByteString          = info.ownId
  val infoHash: ByteString       = info.infoHash
  var iHave: BitSet              = BitSet.empty
  var peerHas: BitSet            = BitSet.empty

  // Need to keep mutable state
  var keepAlive                    = false
  var immediatePostHandshake       = true
  var amChoking, peerChoking       = true
  var amInterested, peerInterested = false

  // Depending on if peerId is None or Some, then that dictates whether this
  // actor initiates a handshake with a peer, or waits for the peer to send a
  // handshake over
  override def preStart(): Unit = {
    peerId match {
      case Some(id) =>
        protocol ! BT.Handshake(infoHash, id)
        become(initiatedHandshake)
      case None =>
        become(waitingForHandshake)
    }
  }

  def waitingForHandshake: Receive = {
    case BT.HandshakeR(infoHash, peerId) if (infoHash == this.infoHash) =>
      protocol ! BT.Handshake(infoHash, ownId)
      parent ! TorrentM.Register(peerId)
      this.peerId = Some(peerId)
      heartbeat
      become(receive)
    case _ => context stop self
  }

  def initiatedHandshake: Receive = {
    case BT.Handshake(infoHash, peerId)
        if (infoHash == this.infoHash && peerId == this.peerId.get) =>
      parent ! TorrentM.Register(peerId)
      heartbeat
      become(receive)
    case _ => context stop self
  }

  def receive = {
    case m: BT.Message => handleMessage(m)
    case r: BT.Reply   => handleReply(r)
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
      case BT.KeepAliveR                      => keepAlive = true
      case BT.ChokeR                          => peerChoking = true
      case BT.UnchokeR                        => peerChoking = false
      case BT.InterestedR                     => peerInterested = true
      case BT.NotInterestedR                  => peerInterested = false
      case update: BT.UpdateR                 => updatePeerAvailable(update)
      case BT.RequestR(index, offset, length) =>
      case BT.PieceR(index, offset, block)    => fileManager ! FM.Write(index, offset, block)
      case BT.CancelR(index, offset, length)  =>
      case _                                  =>
    }
    immediatePostHandshake = false
  }

  def updatePeerAvailable(msg: BT.UpdateR): Unit = {
    msg match {
      case BT.BitfieldR(bitfield) =>
        if (immediatePostHandshake) {
          peerHas |= bitfield
          parent ! TorrentM.Available(Right(peerHas))
        }
      case BT.HaveR(index) =>
        peerHas += index
        parent ! TorrentM.Available(Left(index))
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