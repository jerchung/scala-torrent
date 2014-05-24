package org.jerchung.torrent.actor

import akka.actor.{ Actor, ActorRef, Props, Cancellable }
import akka.actor.Stash
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
  def props(info: PeerInfo, protocolProps: Props, fileManager: ActorRef): Props = {
    Props(new Peer(info, protocolProps, fileManager) with ProdParent with ProdScheduler)
  }

  // This case class encapsulates the information needed to create a peer actor
  case class PeerInfo(
    peerId: Option[ByteString],
    ownId: ByteString,
    infoHash: ByteString,
    ip: String,
    port: Int
  )

  // Encapsulate info of the piece being currently downloaded by the peer
  case class PieceDownload(index: Int = 0, size: Int = 0, var offset: Int = 0)
}

// One of these actors per peer
/* Parent must be Torrent Client */
class Peer(info: PeerInfo, protocolProps: Props, fileManager: ActorRef)
    extends Actor
    with Stash { this: Parent with ScheduleProvider =>

  import context.dispatcher
  import Peer.PieceDownload
  import PieceRequestor.NextBlock

  val MaxRequestPipeline = 5
  val protocol = context.actorOf(protocolProps)

  // Reference for a pieceRequestor
  var requestor: Option[ActorRef] = None

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

  // A peer can only download one piece at a time
  // Keep track of the information in the currently downloading piece
  var currentPieceDownload = PieceDownload()
  var pipelinedRequests = Set[Int]()


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
    peerId map { id => parent ! PeerM.Disconnected(id, peerHas) }
  }

  def waitingForHandshake: Receive = {
    case BT.HandshakeR(infoHash, peerId) if (infoHash == this.infoHash) =>
      protocol ! BT.Handshake(infoHash, ownId)
      parent ! PeerM.Connected(peerId)
      this.peerId = Some(peerId)
      heartbeat
      context.become(acceptBitfield)

    case _ => context stop self
  }

  def initiatedHandshake: Receive = {
    case BT.HandshakeR(infoHash, peerId)
        if (infoHash == this.infoHash && peerId == this.peerId.get) =>
      parent ! PeerM.Connected(peerId)
      heartbeat
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
  def acceptBitfield: Receive = {
    case BT.BitfieldR(bitfield) =>
      peerHas |= bitfield
      parent ! TorrentM.Available(Right(peerHas))
      context.become(receive)

    case msg =>
      receive(msg)
      msg match {
        case BT.Reply => context.become(receive)
        case _ => // Do nothing
      }
  }

  /*
   * When choked ignore all messages from the client besides the UnchokeR message
   * which will cause actor to return to recieve state.  Stash any message sent
   * from client for unstashing when returning to receive state.
   */
  def choked: Receive = {
    case BT.UnchokeR =>
      unstashAll()
      parent ! PeerM.Ready(peerHas)
      context.become(receive)
    case m: BT.Message =>
      stash()
  }

  def receive = {

    case m: BT.Message =>
      // Don't need to send KeepAlive message if already sending another message
      keepAliveTask map { _.cancel }
      handleMessage(m)
      keepAliveTask = Some(scheduler.scheduleOnce(1.5 minutes) { sendHeartbeat })

    case r: BT.Reply =>
      keepAlive = true
      handleReply(r)

    // Let's download a piece
    // Create a new PieceRequestor actor which will fire off requests upon
    // construction
    case PeerM.DownloadPiece(idx, size) =>
      requestor = Some(context.actorOf(
        PieceRequestor.props(protocol, idx, size)))
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

      case BT.KeepAliveR =>
        keepAlive = true

      case BT.ChokeR =>
        peerChoking = true
        context.become(choked)

      // Upon being unchoked, peer should send ready message to parent to decide
      // what piece to downoad
      case BT.UnchokeR =>
        peerChoking = false
        parent ! PeerM.Ready(peerHas)

      case BT.InterestedR =>
        peerInterested = true

      case BT.NotInterestedR =>
        peerInterested = false

      case BT.RequestR(idx, off, len) =>
        if (iHave contains idx && !peerChoking) {
          fileManager ! FM.Read(idx, off, len)
        } else {
          context stop self
        }

      // A part of piece came in due to a request
      case BT.PieceR(idx, off, block) =>
        peerId map { pid => parent ! PeerM.Downloaded(pid, block.length) }
        fileManager ! FM.Write(idx, off, block)
        requestor map { _ ! NextBlock }

      case BT.HaveR(idx) =>
        peerHas += idx
        parent ! TorrentM.Available(Left(idx))

      case BT.CancelR(idx, off, len) =>
      case _ =>
    }
  }

  // Inspect currentPieceDownload and do appropriate actions depending on state
  // Pipeline requests if necessary
  def requestNextBlock: Unit = {
    val numNewRequests = MaxRequestPipeline - pipelinedRequests.length
    val currentOffset = currentPieceDownload.offset
    val idx = currentPieceDownload.index
    val size = currentPieceDownload.size

    (0 until numNewRequests) foreach { i =>
      self ! BT.Request(idx, )
    }

    @tailrec
    def pipelineRequest(
        count: Int,
        requests: Set[Int] = Set[Int]()): Set[Int] = {
      if (count == numNewRequests) {
        requests
      } else {
        val offset = currentOffset +
          (count * (Constant.BlockSize min (size - offset)))
        self ! BT.Request(idx, currentOffset)
      }
    }


    val offset = currentPieceDownload.offset
    val size = currentPieceDownload.size
    val index = currentPieceDownload.index
    if (offset < size) {
      self ! BT.Request(index, offset, Constant.BlockSize min (size - offset))
    }

    currentPieceDownload.offset += Constant.BlockSize
  }

  // Start off the scheduler to send keep-alive signals every 2 minutes and to
  // check that keep-alive is being sent to itself from the peer
  def heartbeat: Unit = {

    def checkHeartbeat: Unit = {
      if (keepAlive) {
        keepAlive = false
        scheduler.scheduleOnce(3 minutes) { checkHeartbeat }
      } else {
        parent ! "No KeepAlive"
        context stop self
      }
    }

    checkHeartbeat()
    sendHeartbeat()
  }

  def sendHeartbeat: Unit = {
    protocol ! BT.KeepAlive
    keepAliveTask = Some(scheduleOnce(1.5 minutes) { sendHeartbeat })
  }


}