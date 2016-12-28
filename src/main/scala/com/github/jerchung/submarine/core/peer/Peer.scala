package com.github.jerchung.submarine.core.peer

import akka.actor._
import akka.event.EventStream
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.Core.{AppSchedulerService, SchedulerService}
import com.github.jerchung.submarine.core.base.{Core, Torrent}
import com.github.jerchung.submarine.core.implicits.Convert._
import com.github.jerchung.submarine.core.piece.PiecePipeline
import com.github.jerchung.submarine.core.protocol.TorrentProtocol
import com.github.jerchung.submarine.core.state.TorrentState

import scala.collection.BitSet
import scala.concurrent.duration._
import scala.language.postfixOps

object Peer {
  def props(args: Args): Props = {
    Props(new Peer(args) with AppCake)
  }

  case class Args(protocol: ActorRef,
                  peerId: ByteString,
                  ownId: ByteString,
                  ip: String,
                  port: Int,
                  torrent: Torrent,
                  torrentEvents: EventStream)

  trait Provider {
    def peer(args: Peer.Args): ActorRef
  }

  trait AppProvider extends Provider { this: Core.AppProvider =>
    override def peer(args: Peer.Args): ActorRef =
      context.actorOf(Peer.props(args))
  }

  trait Cake extends SchedulerService { this: Peer =>
    def provider: TorrentProtocol.Provider
  }

  trait AppCake extends Cake with AppSchedulerService { this: Peer =>
    val provider = new Core.AppProvider(context) with TorrentProtocol.AppProvider
  }

  sealed trait Publish
  case class Announce(peer: ActorRef, message: Publish) extends TorrentState.Relevant
  case class IsReady(peerHas: BitSet) extends Publish
  case class Interested(interested: Boolean) extends Publish
  case class Downloaded(bytes: Int) extends Publish
  case class Uploaded(bytes: Int) extends Publish
  case class Connected(args: Args) extends Publish
  case class ReadyForPiece(peerHas: BitSet) extends Publish
  case class Disconnected(id: ByteString, ip: String, peerHas: BitSet) extends Publish
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
                      pipeline: ActorRef,
                      torrentEvents: EventStream) {

    // We want all sender references to protocol / pipeline to be from the peer
    implicit val sender: ActorRef = peer

    // (offset, length)
    private var inFlight: Set[(Int, Int)] = Set()

    connectPeer()

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
        torrentEvents.publish(Peer.Downloaded(piece.block.length))
      }
    }

    def request(request: TorrentProtocol.Send.Request): Unit = {
      if (request.index == index) {
        protocol ! request
        inFlight += (request.offset -> request.length)
      }
    }

    def cancelAll(): Unit = {
      inFlight.foreach { case (offset, length) =>
        protocol ! TorrentProtocol.Send.Cancel(index, offset, length)
      }

      inFlight = Set()
    }

    def connectPeer(): Unit = {
      pipeline ! PiecePipeline.Attach(peer)
    }

    def disconnect(): Unit = {
      pipeline ! PiecePipeline.Detach(peer)
    }
  }
}

/**
  * Peer will start either as a HandshakeInitPeer (send handshake first) or a ReceivedHandshakePeer (already received a
  * handshake request)
  * @param args
  */
abstract class Peer(args: Peer.Args) extends Actor with ActorLogging {
    this: Peer.Cake =>

  import TorrentProtocol.{Reply, Send}
  import context.dispatcher

  val protocol: ActorRef = args.protocol

  // Reference for a BlockRequestor
  var pieceRequestPipeline: Option[ActorRef] = None

  val MaxInvalidPieces = 2
  var numInvalidPieces = 0

  val torrentEvents: EventStream = args.torrentEvents

  var pieceRequests: Option[Peer.PieceRequests] = None

  var peerId: ByteString   = args.peerId
  val ip: String           = args.ip
  val port: Int            = args.port
  val ownId: ByteString    = args.ownId
  val infoHash: ByteString = ByteString(args.torrent.infoHash)
  val numPieces: Int       = args.torrent.numPieces
  var iHave: BitSet        = BitSet.empty
  var peerHas: BitSet      = BitSet.empty

  // Need to keep mutable state
  var amChoking, peerChoking       = true
  var amInterested, peerInterested = false
  var isSeed                       = false
  var bitfieldSent                 = false

  // KeepAlive related
  var keepAliveReceived = true
  var keepAliveSendTask: Option[Cancellable] = None

  override def preStart(): Unit = {
    context.watch(protocol)
    protocol ! TorrentProtocol.Command.SetPeer(self)

    peerPublish(Peer.Connected(args))
    args.torrentEvents.subscribe(self, classOf[Peer.Message.IHave])

    context.become(acceptBitfield)
    checkKeepAlive()
    sendKeepAlive()
  }

  override def postStop(): Unit = {
    peerPublish(Peer.Disconnected(peerId, ip, peerHas))
    pieceRequests.foreach { _.disconnect() }
    keepAliveSendTask.foreach(_.cancel())
    protocol ! PoisonPill
  }

  /**
   * Bitfield must be first reply message sent to you from the peer for it to be valid
   * Can also accept messages from client, but then stays in acceptBitfield state
   */
  def acceptBitfield: Receive = receiveSend orElse receivePeerMessage orElse receiveOther orElse {
    case Reply.Bitfield(bitfield) =>
      peerHas |= bitfield
      seedCheck(peerHas)
      peerPublish(Peer.Available(Right(peerHas)))
      context.become(receive)

    case msg: Reply =>
      receiveReply(msg)
      context.become(receive)
  }

  // Default receive behavior for messages meant to be forwarded to peer
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
      // Can't already be downloading something
      if (pieceRequests.isEmpty) {
        pieceRequests = Some(new Peer.PieceRequests(index, self, protocol, piecePipeline, args.torrentEvents))
      }

    case Peer.Message.IHave(pieces) =>
      // Check if the piece we're currently downloading is finished, request for another piece if applicable
      pieceRequests.foreach { pr =>
        if (pieces.contains(pr.index)) {
          pieceRequests = None
          requestNextPieceIfApplicable()
        }
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
    case PiecePipeline.Invalid(index) =>
      numInvalidPieces += 1

      // TODO(jerry) - Blacklist the IP so that this peer can never connect again for this particular torrent
      if (numInvalidPieces < MaxInvalidPieces) {
        requestNextPieceIfApplicable()
      } else {
        context.stop(self)
      }

    case Terminated(actor) if actor == protocol =>
      // Connection done, time to stop
      context.stop(self)
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
        if (!peerChoking)
          pieceRequests.foreach { _.request(req) }
        shouldSend = false

      case cancel: Send.Cancel =>
        pieceRequests.foreach { _.cancel(cancel) }
        shouldSend = false

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
        pieceRequests.foreach { _.disconnect() }

      // If this peer was in the middle of downloading a piece before it was choked and that piece isn't done downloading
      // yet, we should restart downloading it
      case Reply.Unchoke =>
        peerChoking = false
        pieceRequests match {
          case Some(pr) => pr.connectPeer()
          case None => requestNextPieceIfApplicable()
        }

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
        pieceRequests.foreach(_.piece(piece))

      case Reply.Have(idx) =>
        peerHas += idx
        seedCheck(peerHas)
        peerPublish(Peer.Available(Left(idx)))

      case Reply.Cancel(idx, off, len) =>
         // Don't really know what to do here LOL

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
      scheduler.scheduleOnce(3.minutes) {
        self ! Peer.CheckKeepAlive
      }
    }
  }

  def sendKeepAlive(): Unit = {
    keepAliveSendTask.foreach { _.cancel() }
    keepAliveSendTask = Some(scheduler.scheduleOnce(1.minute) {
      protocol ! Send.KeepAlive
      sendKeepAlive()
    })
  }

  def peerLog(message: String): String = {
    s"[Peer ${peerId.toChars}] $message"
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
