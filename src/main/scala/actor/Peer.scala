package org.jerchung.torrent.actor

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Cancellable
import akka.actor.Props
import akka.actor.PoisonPill
import akka.actor.Stash
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import akka.util.Timeout
import com.escalatesoft.subcut.inject._
import org.jerchung.torrent.actor.message.{ PeerM, BT, TorrentM, FM }
import org.jerchung.torrent.actor.Peer.PeerInfo
import org.jerchung.torrent.Constant
import org.jerchung.torrent.dependency.BindingKeys._
import scala.annotation.tailrec
import scala.collection.BitSet
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

object Peer {
  def props(
      info: PeerInfo,
      protocolProps: Props,
      router: ActorRef)
      (implicit bindingModule: BindingModule): Props = {
    Props(new Peer(info, protocolProps, router))
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
  with AutoInjectable {

  import context.dispatcher
  import context.system
  import BlockRequestor._

  val MaxRequestPipeline = 5
  val protocol = injectOptional [ActorRef](TorrentProtocolId) getOrElse {
    context.actorOf(protocolProps)
  }

  // Reference for a BlockRequestor
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
      case Some(pid) =>
        protocol ! BT.Handshake(infoHash, ownId)
        context.become(initiatedHandshake(infoHash, pid))
      case None =>
        context.become(waitingForHandshake(infoHash))
    }
  }

  override def postStop(): Unit = {
    router ! PeerM.Disconnected(peerHas)
  }


  def waitingForHandshake(testInfoHash: ByteString): Receive = {
    case BT.HandshakeR(infoHash, peerId) if (infoHash == testInfoHash) =>
      protocol ! BT.Handshake(infoHash, ownId)
      router ! PeerM.Connected
      this.peerId = Some(peerId)
      heartbeat()
      context.become(acceptBitfield)

    case _ => context stop self
  }

  def initiatedHandshake(testInfoHash:ByteString, testId: ByteString): Receive = {
    case BT.HandshakeR(infoHash, peerId) if (infoHash == testInfoHash && peerId == testId) =>
      router ! PeerM.Connected
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
      keepAliveTask = Some(system.scheduler.scheduleOnce(1.5 minutes) {
        sendHeartbeat()
      })
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
    // Create a new BlockRequestor actor which will fire off requests upon
    // construction
    case PeerM.DownloadPiece(idx, size) =>
      currentPieceIndex = idx
      requestor = Some(createBlockRequestor(idx, size))

    // Peer was being choked, and another peer took up the piece this peer was
    // downloading before it was choked, so have to stop downloding this piece
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
  def choked(stashed: List[BT.Message]): Receive = receiveOther orElse {
    case BT.UnchokeR =>
      requestor match {
        case None => router ! PeerM.ReadyForPiece(peerHas)
        case Some(req) => router ! PeerM.Resume(currentPieceIndex)
      }
      stashed.reverse.foreach { receive }
      context.become(receive)
    case r: BT.Reply =>
      handleReply(r)
    case m: BT.Message =>
      context.become(choked(m :: stashed))
  }

  // Composition of all the default receive behaviors
  def receive = receiveMessage orElse receiveReply orElse receiveOther

  /*
  * Update state according to message and then send message along to protocol
  * to be send over the wire to the peer in ByteString form
  */
  def handleMessage(message: BT.Message): Unit = {
    message match {
      case BT.Choke =>
        amChoking = true
      case BT.Unchoke if (!amChoking) =>
        return
      case BT.Unchoke =>
        amChoking = false
      case BT.Interested =>
        amInterested = true
      case BT.NotInterested =>
        amInterested = false
      case BT.Have(index) =>
        iHave += index
      case BT.Bitfield(bitfield, numPieces) =>
        iHave = bitfield
      case _ =>
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
        context.become(choked(List[BT.Message]()))

      // Upon being unchoked, peer should send ready message to parent to decide
      // what piece to downoad
      case BT.UnchokeR =>
        peerChoking = false
        router ! PeerM.ReadyForPiece(peerHas)

      case BT.InterestedR =>
        peerInterested = true
        router ! BT.InterestedR

      case BT.NotInterestedR =>
        peerInterested = false
        router ! BT.NotInterestedR

      case BT.RequestR(idx, off, len) =>
        if (iHave.contains(idx) && !amChoking) {
          router ! FM.Read(idx, off, len)
        } else {
          context stop self
        }

      // A part of piece came in due to a request
      case BT.PieceR(idx, off, block) =>
        router ! PeerM.Downloaded(block.length)
        router ! FM.Write(idx, off, block)
        requestor map { _ ! BlockDoneAndRequestNext(off) }

      case BT.HaveR(idx) =>
        peerHas += idx
        router ! PeerM.PieceAvailable(Left(idx))

      case BT.CancelR(idx, off, len) =>

      case _ =>
    }
  }

  // Returns actorRef of BlockRequestor Actor, also has depedency injection
// logic
  def createBlockRequestor(index: Int, size: Int): ActorRef = {
    injectOptional [ActorRef](BlockRequestorId) getOrElse {
      context.actorOf(BlockRequestor.props(index, size))
    }
  }

  // Reset piece index to -1 and end requestor actor.  Clear all references
  def endCurrentPieceDownload(): Unit = {
    currentPieceIndex = -1
    requestor foreach { _ ! PoisonPill }
    requestor = None
  }

  // Start off the scheduler to send keep-alive signals every 2 minutes and to
  // check that keep-alive is being sent to itself from the peer
  def heartbeat(): Unit = {

    def checkHeartbeat(): Unit = {
      if (keepAlive) {
        keepAlive = false
        system.scheduler.scheduleOnce(3 minutes) { checkHeartbeat }
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
    keepAliveTask = Some(system.scheduler.scheduleOnce(1.5 minutes) {
      sendHeartbeat()
    })
  }


}