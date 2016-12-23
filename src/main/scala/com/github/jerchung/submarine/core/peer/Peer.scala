package com.github.jerchung.submarine.core.peer

import akka.actor._
import akka.event.EventStream
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.Core.MessageBus
import com.github.jerchung.submarine.core.base.{Core, Torrent}
import com.github.jerchung.submarine.core.implicits.Convert._
import com.github.jerchung.submarine.core.piece.{PieceData, PiecePipeline}
import com.github.jerchung.submarine.core.protocol.TorrentProtocol.Send
import com.github.jerchung.submarine.core.protocol.TorrentProtocol
import com.github.jerchung.submarine.core.state.TorrentState

import scala.collection.BitSet
import scala.concurrent.duration._
import scala.language.postfixOps

object Peer {
  case class Args(connection: ActorRef,
                  peerId: Option[ByteString],
                  ownId: ByteString,
                  ip: String,
                  port: Int,
                  torrent: Torrent,
                  torrentEvents: EventStream) extends MessageBus

  trait Provider extends Core.Cake#Provider {
    def handshakeInitPeer(args: Peer.Args): ActorRef
    def waitHandshakePeer(args: Peer.Args): ActorRef
  }

  trait AppProvider extends Provider {
    override def handshakeInitPeer(args: Args): ActorRef =
      context.actorOf(HandshakeInitPeer.props(args))

    override def waitHandshakePeer(args: Args): ActorRef =
      context.actorOf(WaitHandshakePeer.props(args))
  }

  trait Cake extends Core.Cake { this: Peer =>
    def provider: Provider
    trait Provider extends Core.SchedulerProvider
                   with TorrentProtocol.Provider
  }

  trait AppCake extends Cake { this: Peer =>
    val provider = new Provider with Core.AppSchedulerProvider
                                with TorrentProtocol.AppProvider
  }

  sealed trait Publish
  case class Announce(peer: ActorRef, message: Publish) extends TorrentState.Relevant
  case class IsReady(peerHas: BitSet) extends Publish
  case class Interested(interested: Boolean) extends Publish
  case class Downloaded(bytes: Int) extends Publish
  case class Uploaded(bytes: Int) extends Publish
  case class Connected(args: Args) extends Publish
  case class ReadyForPiece(peerHas: BitSet) extends Publish
  case class Disconnected(id: ByteString, peerHas: BitSet) extends Publish
  case class ChokedOnPiece(index: Int) extends Publish
  case class Resume(index: Int) extends Publish
  case class Available(pieces: Either[Int, BitSet]) extends Publish
  case class PieceDone(idx: Int, piece: Array[Byte]) extends Publish
  case class PieceInvalid(idx: Int) extends Publish
  case class RequestPiece(index: Int, offset: Int, length: Int) extends Publish

  case object ClearPiece extends Publish
  case object IsSeed extends Publish
  case object CheckKeepAlive extends Publish

  sealed trait Message
  object Message {
    case class Interested(isInterested: Boolean) extends Message
    case class IHave(pieces: BitSet) extends Message
    case class DownloadPiece(index: Int,
                             piecePipeline: ActorRef) extends Message
    case class Choke(amChoking: Boolean) extends Message
    case class PieceBlock(index: Int, offset: Int, data: ByteString) extends Message
    case object CheckKeepAlive extends Message
  }

  class PieceRequests(val index: Int,
                      peer: ActorRef,
                      protocol: ActorRef,
                      pipeline: ActorRef) {

    // We want all sender references to protocol / pipeline to be from the peer
    implicit val sender: ActorRef = peer

    // (offset, length)
    private var inFlight: Set[(Int, Int)] = Set()

    def cancel(cancel: TorrentProtocol.Send.Cancel): Unit = {
      if (cancel.index == index && inFlight.contains((cancel.offset, cancel.length))) {
        protocol ! cancel
        inFlight -= (cancel.offset -> cancel.length)
      }
    }

    def piece(piece: TorrentProtocol.Reply.Piece): Unit = {
      if (piece.index == index && inFlight.contains((piece.offset, piece.block.length))) {
        pipeline ! piece
        inFlight -= (piece.offset -> piece.block.length)
      }
    }

    def request(request: TorrentProtocol.Send.Request): Unit = {
      if (request.index == index) {
        inFlight += (request.offset -> request.length)
      }
    }

    def cancelAll(): Unit = {
      inFlight.foreach { case (offset, length) =>
        protocol ! TorrentProtocol.Send.Cancel(index, offset, length)
      }

      inFlight = Set()
    }
  }
}

object HandshakeInitPeer {
  def props(args: Peer.Args): Props = {
    Props(new HandshakeInitPeer(args) with Peer.AppCake)
  }
}

class HandshakeInitPeer(args: Peer.Args) extends Peer(args) { this: Peer.Cake =>
  override def preStart(): Unit = {
    protocol ! Send.Handshake(infoHash, ownId)
    context.become(handshake(initiatedHandshake))
  }
}

object WaitHandshakePeer {
  def props(args: Peer.Args): Props = {
    Props(new WaitHandshakePeer(args) with Peer.AppCake)
  }
}

class WaitHandshakePeer(args: Peer.Args) extends Peer(args) { this: Peer.Cake =>
  override def preStart(): Unit = {
    context.become(handshake(waitingForHandshake))
  }
}

// One of these actors per com.github.jerchung.submarine.core.peer
// announce actorRef is a PeerRouter actor which will forward the messages
// to the correct actors
abstract class Peer(args: Peer.Args) extends Actor with ActorLogging {
    this: Peer.Cake =>

  import TorrentProtocol.{Reply, Send}
  import context.dispatcher

  val MaxRequestPipeline = 5
  val protocol: ActorRef = provider.torrentProtocol(TorrentProtocol.Args(
      self, args.connection
  ))

  // Reference for a BlockRequestor
  var pieceRequestPipeline: Option[ActorRef] = None

  val MaxInvalidPieces = 2
  var numInvalidPieces = 0

  val torrentEvents: EventStream = args.torrentEvents

  var pieceRequests: Option[Peer.PieceRequests] = None

  var peerId: Option[ByteString] = args.peerId
  val ip: String                 = args.ip
  val port: Int                  = args.port
  val ownId: ByteString          = args.ownId
  val infoHash: ByteString       = ByteString(args.torrent.infoHash)
  val numPieces: Int             = args.torrent.numPieces
  var iHave: BitSet              = BitSet.empty
  var peerHas: BitSet            = BitSet.empty

  // Need to keep mutable state
  var amChoking, peerChoking       = true
  var amInterested, peerInterested = false
  var isSeed                       = false
  var bitfieldSent                 = false

  // KeepAlive related
  var keepAliveReceived = true
  var keepAliveSendTask: Option[Cancellable] = None

  override def postStop(): Unit = {
    peerId foreach { id => peerPublish(Peer.Disconnected(id, peerHas)) }
    keepAliveSendTask.foreach(_.cancel())
  }

  def handshake(handshakeState: Receive): Receive = handshakeState orElse handshakeFail

  def handshakeFail: Receive = {
    case msg =>
      log.warning(peerLog(s"Waiting for a handshake message for peer " +
        s"got $msg instead.  Now stopping peer"))
      context.stop(self)
  }

  def waitingForHandshake: Receive = {
    case Reply.Handshake(_infoHash, _peerId) if _infoHash == this.infoHash =>
      protocol ! Send.Handshake(infoHash, ownId)
      afterHandshake(_infoHash, _peerId)
  }

  def initiatedHandshake: Receive = {
    case Reply.Handshake(_infoHash, _peerId) if _infoHash == infoHash =>
      afterHandshake(_infoHash, _peerId)
  }

  def afterHandshake(infoHash: ByteString, peerId: ByteString): Unit = {
    this.peerId = Some(peerId)
    peerPublish(Peer.Connected(args.copy(peerId = Some(peerId))))
    args.torrentEvents.subscribe(self, classOf[Peer.Message.IHave])
    checkKeepAlive()
    sendKeepAlive()
    context.become(acceptBitfield)
  }

  /**
   * Bitfield must be first reply message sent to you from the peer for it to be valid
   * Can also accept messages from client, but then stays in acceptBitfield state
   */
  def acceptBitfield: Receive = receiveSend orElse receivePeerMessage orElse {
    case Reply.Bitfield(bitfield) =>
      peerHas |= bitfield
      seedCheck(peerHas)
      peerPublish(Peer.Available(Right(peerHas)))
      context.become(receive)

    case msg: Reply =>
      receiveReply(msg)
      context.become(receive)
  }

  // Default receive behavior for messages meant to be forwarded to com.github.jerchung.submarine.core.peer
  def receiveSend: Receive = {
    case m: Send =>
      // log.debug(peerLog(s"Handling message $m"))
      // Don't need to send KeepAlive message if already sending another message
      handleMessage(m)
      sendKeepAlive()
  }

  // Default receive behavior for messages from protocol
  def receiveReply: Receive = {
    case r: Reply =>
      // log.debug(peerLog(s"Received reply $r"))
      keepAliveReceived = true
      handleReply(r)
  }

  // Default receive behavior for messages sent from other actors
  def receivePeerMessage: Receive = {
    case Peer.Message.DownloadPiece(index, piecePipeline) =>
      // TODO(jerry) - Fix case where we're already downloading something
      piecePipeline ! PiecePipeline.Attach(self)
      pieceRequests = Some(new Peer.PieceRequests(index, self, protocol, piecePipeline))

    case Peer.Message.IHave(pieces) =>
      // Get notified that our current piece download finished, can now request another one (if applicable)
      if (pieceRequests.isDefined && pieces.contains(pieceRequests.get.index)) {
        pieceRequests = None
        requestNextPieceIfApplicable()
      }

      if (bitfieldSent) {
        val extraIndexes: BitSet = pieces &~ iHave

        extraIndexes.foreach { index =>
          protocol ! TorrentProtocol.Send.Have(index)
        }
      } else {
        protocol ! TorrentProtocol.Send.Bitfield(pieces, pieces.size)
      }

      iHave = pieces
      bitfieldSent = true

    case Peer.Message.Interested(isInterested) =>
      val message: TorrentProtocol.Send = if (isInterested)
        TorrentProtocol.Send.Interested
      else
        TorrentProtocol.Send.NotInterested

      protocol ! message

    case Peer.Message.Choke(choking) =>
      // If current choking state and new state are different
      if (choking ^ amChoking) {
        amChoking = choking

        val message = if (choking)
          TorrentProtocol.Send.Choke
        else
          TorrentProtocol.Send.Unchoke

        protocol ! message
      }

    case Peer.CheckKeepAlive =>
      if (keepAliveReceived) {
        keepAliveReceived = false
      }
  }

  def receiveOther: Receive = {
    case PieceData.Complete(index, piece) =>
      peerPublish(Peer.PieceDone(index, piece))
      requestNextPieceIfApplicable()

    case PieceData.Invalid(index) =>
      peerPublish(Peer.PieceInvalid(index))
      numInvalidPieces += 1

      // TODO(jerry) - Blacklist the IP so that this peer can never connect again for this particular torrent
      if (numInvalidPieces < MaxInvalidPieces) {
        requestNextPieceIfApplicable()
      } else {
        context.stop(self)
      }
  }

  // Composition of all the default receive behaviors
  def receive: Receive = receiveSend orElse receiveReply orElse receivePeerMessage

  /*
  * Update state according to message and then send message along to protocol
  * to be send over the wire to the peer in ByteString form
  */
  def handleMessage(message: Send): Unit = {
    var shouldSend = true
    message match {
      case Send.Choke => amChoking = true
      case Send.Unchoke => amChoking = false
      case Send.NotInterested =>
        log.debug(peerLog(s"Not interested anymore $peerHas"))
        amInterested = false
      case Send.Have(index) => iHave += index
      case Send.Bitfield(bitfield, _) => iHave = bitfield
      case Send.Interested if peerInterested => shouldSend = false
      case Send.Interested => amInterested = true
      case req: Send.Request =>
        if (peerChoking)
          shouldSend = false

        pieceRequests.foreach { _.request(req) }

      case _ =>
    }
    if (shouldSend) { protocol ! message }
  }

  def handleReply(reply: Reply): Unit = {
    reply match {
      case Reply.KeepAlive =>
        keepAliveReceived = true

      // Upon peer being choked, report which piece, if any, is being downloaded
      // to keep possibility of resuming piece download upon possible unchoking
      case Reply.Choke =>
        peerChoking = true
        pieceBuilder.foreach { p =>
          peerPublish(Peer.ChokedOnPiece(p.index))
          p.pipeline ! PoisonPill
        }

        pieceBuilder = None

      // Upon being unchoked, peer should send ready message to parent to decide
      // what piece to downoad
      case Reply.Unchoke =>
        peerChoking = false
        requestNextPieceIfApplicable()

      case Reply.Interested =>
        peerInterested = true

      case Reply.NotInterested =>
        peerInterested = false

      case Reply.Request(idx, off, len) =>
        if (iHave.contains(idx) && !amChoking) {
          peerPublish(Peer.RequestPiece(idx, off, len))
        }

      // A part of piece came in due to a request
      case piece: Reply.Piece =>
        pieceRequests.foreach { pr =>
          if (pr.index == piece.index) {
            pr.piece(piece)
            peerPublish(Peer.Downloaded(piece.block.length))
          }
        }

      case Reply.Have(idx) =>
        peerHas += idx
        seedCheck(peerHas)
        peerPublish(Peer.Available(Left(idx)))

      case Reply.Cancel(idx, off, len) =>

      case _ =>
    }
  }

  def seedCheck(peerHas: BitSet): Unit = {
    if (peerHas.size == numPieces) {
      isSeed = true
      peerPublish(Peer.IsSeed)
    }
  }

  def checkKeepAlive(): Unit = {
    if (keepAliveReceived) {
      keepAliveReceived = false
      provider.scheduler.scheduleOnce(3.minutes) {
        self ! Peer.CheckKeepAlive
      }
    }
  }

  def sendKeepAlive(): Unit = {
    keepAliveSendTask.foreach { _.cancel() }
    keepAliveSendTask = Some(provider.scheduler.scheduleOnce(1.minute) {
      protocol ! Send.KeepAlive
      sendKeepAlive()
    })
  }

  def peerLog(message: String): String = {
    peerId match {
      case Some(pid) => s"[Peer ${pid.toChars}] $message"
      case None => message
    }
  }

  private def requestNextPieceIfApplicable(): Unit = {
    if (!peerChoking) {
      peerPublish(Peer.IsReady(peerHas))
    }
  }

  private def peerPublish(message: Peer.Publish): Unit = {
    torrentEvents.publish(Peer.Announce(self, message))
  }
}
