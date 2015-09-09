package storrent.peer

import akka.actor.{ Actor, ActorRef, Props, Cancellable, ActorLogging }
import akka.actor.PoisonPill
import akka.actor.Stash
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import akka.util.Timeout
import storrent.message.{ PeerM, BT, TorrentM, FM }
import storrent.Constant
import scala.annotation.tailrec
import scala.collection.BitSet
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps
import storrent.Convert._

// import Peer.PeerConfig

// This case class encapsulates the information needed to create a peer actor
case class PeerConfig(
  peerId: Option[ByteString],
  ownId: ByteString,
  infoHash: ByteString,
  ip: String,
  port: Int,
  numPieces: Int,
  handshake: HandshakeState
)

sealed trait HandshakeState
case object InitHandshake extends HandshakeState
case object WaitHandshake extends HandshakeState

object Peer {
  def props(pConfig: PeerConfig, connection: ActorRef, router: ActorRef): Props = {
    Props(new Peer(pConfig, connection, router) with Peer.AppCake)
  }

  trait Cake { this: Peer =>
    def provider: Provider
    trait Provider {
      def protocol(peer: ActorRef, connection: ActorRef): ActorRef
      def blockRequestor(protocol: ActorRef, index: Int, size: Int): ActorRef
    }
  }

  trait AppCake extends Cake { this: Peer =>
    override object provider extends Provider {
      def protocol(peer: ActorRef, connection: ActorRef) =
        context.actorOf(TorrentProtocol.props(peer, connection))
      def blockRequestor(protocol: ActorRef, index: Int, size: Int) =
        context.actorOf(BlockRequestor.props(protocol, index, size))
    }
  }
}

// One of these actors per peer
// router actorRef is a PeerRouter actor which will forward the messages
// to the correct actors
class Peer(pConfig: PeerConfig, connection: ActorRef, router: ActorRef)
  extends Actor with ActorLogging {
  this: Peer.Cake =>

  import context.dispatcher
  import BlockRequestor.Message.{ Resume, BlockDoneAndRequestNext }

  val MaxRequestPipeline = 5
  val protocol = provider.protocol(self, connection)
  val scheduler = context.system.scheduler
  // Reference for a BlockRequestor
  var requestor: Option[ActorRef] = None

  // index of piece currently being downloaded.  If -1 then no piece is being
  // downloaded
  var currentPieceIndex = -1

  var peerId: Option[ByteString] = pConfig.peerId
  val ip: String                 = pConfig.ip
  val port: Int                  = pConfig.port
  val ownId: ByteString          = pConfig.ownId
  val infoHash: ByteString       = pConfig.infoHash
  val numPieces: Int             = pConfig.numPieces
  var iHave: BitSet              = BitSet.empty
  var peerHas: BitSet            = BitSet.empty

  // Need to keep mutable state
  var keepAlive                    = true
  var amChoking, peerChoking       = true
  var amInterested, peerInterested = false
  var isSeed                       = false

  // Keep reference to KeepAlive sending task
  var keepAliveTask: Option[Cancellable] = None

  // State depends on if the client connected to the peer or the peer is connecting
  // through the tracker
  override def preStart(): Unit = {
    pConfig.handshake match {
      case InitHandshake =>
        protocol ! BT.Handshake(infoHash, ownId)
        context.become(handshake(initiatedHandshake))
      case WaitHandshake =>
        context.become(handshake(waitingForHandshake))
    }
  }

  override def postStop(): Unit = {
    peerId foreach { id => router ! PeerM.Disconnected(id, peerHas) }
  }

  def handshake(handshakeState: Receive): Receive = handshakeState orElse handshakeFail

  def handshakeFail: Receive = {
    case msg =>
      log.warning(peerLog(s"Waiting for a handshake message for peer " +
        s"got $msg instead.  Now stopping peer"))
      context.stop(self)
  }

  def waitingForHandshake: Receive = {
    case BT.HandshakeR(infoHash, peerId) if (infoHash == this.infoHash) =>
      protocol ! BT.Handshake(infoHash, ownId)
      this.peerId = Some(peerId)
      router ! PeerM.Connected(pConfig.copy(peerId = Some(peerId)))
      heartbeat()
      context.become(acceptBitfield)
  }

  def initiatedHandshake: Receive = {
    case BT.HandshakeR(infoHash, peerId) if (infoHash == this.infoHash) =>
      this.peerId = Some(peerId)
      router ! PeerM.Connected(pConfig.copy(peerId = Some(peerId)))
      heartbeat()
      context.become(acceptBitfield)
  }

  /**
   * Bitfield must be first reply message sent to you from peer for it to be valid
   * Can also accept messages from client, but then stays in acceptBitfield
   * state
   */
  def acceptBitfield: Receive = receiveMessage orElse {
    case BT.BitfieldR(bitfield) =>
      peerHas |= bitfield
      seedCheck(peerHas)
      router ! PeerM.PieceAvailable(Right(peerHas))
      context.become(receive)

    case msg: BT.Reply =>
      receiveReply(msg)
      context.become(receive)
  }

  // Default receive behavior for messages meant to be forwarded to peer
  def receiveMessage: Receive = {
    case m: BT.Message =>
      // log.debug(peerLog(s"Handling message $m"))
      // Don't need to send KeepAlive message if already sending another message
      keepAliveTask.foreach { _.cancel }
      handleMessage(m)
      sendHeartbeat()
  }

  // Default receive behavior for messages from protocol
  def receiveReply: Receive = {
    case r: BT.Reply =>
      // log.debug(peerLog(s"Received reply $r"))
      keepAlive = true
      handleReply(r)
  }

  // Default receive behavior for messages sent from other actors to peer
  def receiveOther: Receive = {
    // Let's download a piece
    // Create a new BlockRequestor actor which will fire off requests upon
    // construction
    case PeerM.DownloadPiece(idx, size) =>
      log.debug(peerLog(s"Downloading piece $idx size: $size"))
      currentPieceIndex = idx
      requestor = Some(provider.blockRequestor(protocol, idx, size))

    // End the peer if the piece is completed with an invalid hash
    case msg: PeerM.PieceInvalid =>
      router ! msg
      context.stop(self)

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
   * When choked don't want to send any requests for pieces
   */
  def choked: Receive = {
    val choke: Receive = {
      case msg: BT.Request => ()
    }
    choke orElse receive
  }
  // Composition of all the default receive behaviors
  def receive = receiveMessage orElse receiveReply orElse receiveOther

  /*
  * Update state according to message and then send message along to protocol
  * to be send over the wire to the peer in ByteString form
  */
  def handleMessage(message: BT.Message): Unit = {
    var shouldSend = true
    message match {
      case BT.Choke => amChoking = true
      case BT.Unchoke => amChoking = false
      case BT.NotInterested =>
        log.debug(peerLog(s"Not interested anymore $peerHas"))
        amInterested = false
      case BT.Have(index) => iHave += index
      case BT.Bitfield(bitfield, numPieces) => iHave = bitfield
      case BT.Interested if peerInterested => shouldSend = false
      case BT.Interested => amInterested = true
      case BT.Request(_, _, _) if peerChoking => shouldSend = false
      case _ =>
    }
    if (shouldSend) { protocol ! message }
  }

  def handleReply(reply: BT.Reply): Unit = {
    reply match {

      case BT.KeepAliveR =>
        keepAlive = true

      // Upon peer being choked, report which piece, if any, is being downloaded
      // to keep possibility of resuming piece download upon possible unchoking
      case BT.ChokeR =>
        peerChoking = true
        endCurrentPieceDownload()
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
        log.debug(peerLog(s"Received block at piece index $idx offset $off"))
        peerId foreach { pid => router ! PeerM.Downloaded(pid, block.length) }
        router ! FM.Write(idx, off, block)
        requestor foreach { _ ! BlockDoneAndRequestNext(off + block.length) }

      case BT.HaveR(idx) =>
        peerHas += idx
        seedCheck(peerHas)
        router ! PeerM.PieceAvailable(Left(idx))

      case BT.CancelR(idx, off, len) =>
      case _ =>
    }
  }

  def endCurrentPieceDownload(): Unit = {
    if (currentPieceIndex >= 0) {
      router ! PeerM.ChokedOnPiece(currentPieceIndex)
    }
    currentPieceIndex = -1
    requestor foreach { _ ! PoisonPill }
    requestor = None
  }

  def seedCheck(peerHas: BitSet): Unit = {
    if (peerHas.size == numPieces) {
      isSeed = true
      log.debug(peerLog("Is Seed"))
      router ! PeerM.IsSeed
    }
  }

  // Start off the scheduler to send keep-alive signals every 2 minutes and to
  // check that keep-alive is being sent to itself from the peer
  def heartbeat(): Unit = {

    def checkHeartbeat(): Unit = {
      if (keepAlive) {
        keepAlive = false
        scheduler.scheduleOnce(3 minutes) { checkHeartbeat() }
      } else {
        router ! "No KeepAlive"
        context stop self
      }
    }

    checkHeartbeat()
    sendHeartbeat()
  }

  def sendHeartbeat(): Unit = {
    keepAliveTask = Some(scheduler.scheduleOnce(1 minute) {
      log.debug(peerLog("Sending KeepAlive"))
      protocol ! BT.KeepAlive
    })
  }

  def peerLog(message: String): String = {
    peerId match {
      case Some(pid) => s"[Peer ${pid.toChars}] $message"
      case None => message
    }
  }
}
