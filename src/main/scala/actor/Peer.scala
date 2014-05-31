package org.jerchung.torrent.actor

import akka.actor.{ Actor, ActorRef, Props, Cancellable }
import akka.actor.Stash
import akka.actor.PoisonPill
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import akka.util.Timeout
import org.jerchung.torrent.actor.message.{ PeerM, BT, TorrentM, FM }
import org.jerchung.torrent.actor.Peer.PeerInfo
import org.jerchung.torrent.Constant
import scala.annotation.tailrec
import scala.collection.BitSet
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

object Peer {
  def props(info: PeerInfo, protocolProps: Props, router: ActorRef): Props = {
    Props(new Peer(info, protocolProps, router) with ProdParent with ProdScheduler)
  }

  // This case class encapsulates the information needed to create a peer actor
  case class PeerInfo(
    peerId: Option[ByteString],
    ownId: ByteString,
    infoHash: ByteString,
    ip: String,
    port: Int
  )

}

// One of these actors per peer
// router actorRef is a PeerRouter actor which will forward the messages
// to the correct actors
class Peer(info: PeerInfo, protocolProps: Props, router: ActorRef)
    extends Actor
    with Stash { this: Parent with ScheduleProvider =>

  import context.dispatcher
  import PieceRequestor.Message.{ Resume, BlockDoneAndRequestNext }

  val MaxRequestPipeline = 5
  val protocol = context.actorOf(protocolProps)

  // Reference for a pieceRequestor
  var requestor: Option[ActorRef] = None

  // index of piece currently being downloaded.  If -1 then no piece is being
  // downloaded
  var currentPieceIndex = -1

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

  // Keep reference to KeepAlive sending task
  var keepAliveTask: Option[Cancellable] = None

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
    peerId map { id => router ! PeerM.Disconnected(id, peerHas) }
  }


  def waitingForHandshake: Receive = {
    case BT.HandshakeR(infoHash, peerId) if (infoHash == this.infoHash) =>
      protocol ! BT.Handshake(infoHash, ownId)
      router ! PeerM.Connected(peerId)
      this.peerId = Some(peerId)
      heartbeat()
      context.become(acceptBitfield)

    case _ => context stop self
  }

  def initiatedHandshake: Receive = {
    case BT.HandshakeR(infoHash, peerId)
        if (infoHash == this.infoHash && peerId == this.peerId.get) =>
      router ! PeerM.Connected(peerId)
      heartbeat()
      context.become(acceptBitfield)

    /*
     * Get anything besides a handshake reply when waiting for a handshake means
     * that this peer must end itself
     */
    case _ => context stop self
  }

  /**
   * Bitfield must be first reply message sent to you from peer for it to be valid
   * Can also accept messages from client, but then stays in acceptBitfield
   * state
   */
  def acceptBitfield: Receive = receiveMessage orElse {
    case BT.BitfieldR(bitfield) =>
      peerHas |= bitfield
      router ! PeerM.PieceAvailable(Right(peerHas))
      context.become(receive)

    case msg: BT.Reply =>
      receiveReply(msg)
      context.become(receive)
  }

  // Default receive behavior for messages meant to be forwarded to peer
  def receiveMessage: Receive = {
    case m: BT.Message =>
      // Don't need to send KeepAlive message if already sending another message
      keepAliveTask map { _.cancel }
      handleMessage(m)
      keepAliveTask = Some(scheduler.scheduleOnce(1.5 minutes) { sendHeartbeat() })
  }

  // Default receive behavior for messages from protocol
  def receiveReply: Receive = {
    case r: BT.Reply =>
      keepAlive = true
      handleReply(r)
  }

  // Default receive behavior for messages sent from other actors to peer
  def receiveOther: Receive = {

    // Let's download a piece
    // Create a new PieceRequestor actor which will fire off requests upon
    // construction
    case PeerM.DownloadPiece(idx, size) =>
      currentPieceIndex = idx
      requestor = Some(context.actorOf(
        PieceRequestor.props(protocol, idx, size)))

    // Peer was being choked, and another peer took up the piece this peer was
    // downloading before it was choked
    case PeerM.ClearPiece =>
      endCurrentPieceDownload()

    // End the peer if the piece is completed with an invalid hash
    case msg: PeerM.PieceInvalid =>
      router ! msg
      context stop self

    /* If current piece is completed successfully, report to parent and kill
     * current requestor using PoisonPill to avoid memory leak.  Also send the
     * parent a ready message to find out which next piece to download
     */
    case msg: PeerM.PieceDone =>
      router ! msg
      endCurrentPieceDownload()
      router ! PeerM.ReadyForPiece(peerHas)

    case _ => // Do Nothing

  }

  /*
   * When choked ignore all messages from the client besides the UnchokeR message
   * which will cause actor to return to recieve state.  Stash any message sent
   * from client for unstashing when returning to receive state.
   */
  def choked: Receive = {
    case BT.UnchokeR =>
      unstashAll()
      requestor match {
        case None => router ! PeerM.ReadyForPiece(peerHas)
        case Some(req) =>
          req ! Resume
          router ! PeerM.Resume(currentPieceIndex)
      }
      context.become(receive)
    case m: BT.Message =>
      stash()
  }

  // Link up all the default receive behaviors
  def receive = receiveMessage orElse receiveReply orElse receiveOther

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

      case BT.KeepAliveR =>
        keepAlive = true

      // Upon peer being choked, report which piece, if any, is being downloaded
      // to keep possibility of resuming piece download upon possible unchoking
      case BT.ChokeR =>
        peerChoking = true
        if (currentPieceIndex >= 0) {
          router ! PeerM.ChokedOnPiece(currentPieceIndex)
        }
        context.become(choked)

      // Upon being unchoked, peer should send ready message to parent to decide
      // what piece to downoad
      case BT.UnchokeR =>
        peerChoking = false
        router ! PeerM.ReadyForPiece(peerHas)

      case BT.InterestedR =>
        peerInterested = true

      case BT.NotInterestedR =>
        peerInterested = false

      case BT.RequestR(idx, off, len) =>
        if (iHave.contains(idx) && !peerChoking) {
          router ! FM.Read(idx, off, len)
        } else {
          context stop self
        }

      // A part of piece came in due to a request
      case BT.PieceR(idx, off, block) =>
        peerId map { pid => router ! PeerM.Downloaded(pid, block.length) }
        router ! FM.Write(idx, off, block)
        requestor map { _ ! BlockDoneAndRequestNext(off) }

      case BT.HaveR(idx) =>
        peerHas += idx
        router ! PeerM.PieceAvailable(Left(idx))

      case BT.CancelR(idx, off, len) =>
      case _ =>
    }
  }

  // Reset piece index to -1 and end requestor actor
  def endCurrentPieceDownload(): Unit = {
    currentPieceIndex = -1
    requestor map { _ ! PoisonPill }
    requestor = None
  }

  // Start off the scheduler to send keep-alive signals every 2 minutes and to
  // check that keep-alive is being sent to itself from the peer
  def heartbeat(): Unit = {

    def checkHeartbeat(): Unit = {
      if (keepAlive) {
        keepAlive = false
        scheduler.scheduleOnce(3 minutes) { checkHeartbeat }
      } else {
        router ! "No KeepAlive"
        context stop self
      }
    }

    checkHeartbeat()
    sendHeartbeat()
  }

  def sendHeartbeat(): Unit = {
    protocol ! BT.KeepAlive
    keepAliveTask = Some(scheduler.scheduleOnce(1.5 minutes) { sendHeartbeat() })
  }


}