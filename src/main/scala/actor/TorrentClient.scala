package org.jerchung.torrent.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.io.{ IO, Tcp }
import akka.pattern.ask
import akka.util.ByteString
import akka.util.Timeout
import java.net.InetSocketAddress
import java.net.URLEncoder
import org.jerchung.torrent.bencode.Bencode
import org.jerchung.torrent.actor.message.{ TorrentM, TrackerM, BT, PeerM}
import org.jerchung.torrent.actor.Peer.PeerInfo
import org.jerchung.torrent.Constant
import org.jerchung.torrent.Convert._
import org.jerchung.torrent.Torrent
import scala.annotation.tailrec
import scala.collection.BitSet
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

object TorrentClient {

  def props(fileName: String): Props = {
    Props(new TorrentClient(fileName: String) with ProdScheduler)
  }

  case class PeerConnection(id: ByteString, peer: ActorRef, var rate: Double)
      extends Ordered[PeerConnection] {

    // Want the minimum to be highest priority
    // When put into priority queue, max becomes min and vice versa
    def compare(that: PeerConnection): Int = {
      -1 * rate.compare(that.rate)
    }
  }

  case class PieceInfo(index: Int, var count: Int)
      extends Ordered[PieceInfo] {
    def compare(pieceCount: PieceInfo): Int = {
      count.compare(pieceCount.count)
    }
  }

  /*
   * Class used to keep track of the frequency of each piece as well as provide
   * an interface to get a sorted list of the pieces and to add and remove pieces
   * at will
   */
  class SortedPieces {

    // Set of pieces ordered by frequency starting from rarest
    private val piecesSet = mutable.SortedSet[PieceInfo].empty

    // Map of piece index -> pieceInfo to provide access
    private val piecesMap = mutable.Map[Int, PieceInfo].empty

    def add(piece: PieceInfo): Unit = {
      piecesMap(piece.index) = piece
      piecesSet += piece
    }

    // Given the index of a piece, remove said piece, then return the piece
    def remove(index: Int): PieceInfo = {
      val piece = piecesMap(index)
      piecesMap -= index
      piecesSet -= piece
      piece
    }

    // Update the frequency of the piece of a given index
    def update(index: Int, count: Int): Unit = {
      val piece = remove(index)
      piece.count += count
      add(piece)
    }

    // Perform the jittering of choosing from k of the rarest pieces whose
    // indexes are contained in possibles
    // Return the index of the chosen piece
    def rarest(possibles: BitSet, k: Int): Int = {
      val availablePiecesBuffer = mutable.ArrayBuffer[Int]()

      @tailrec
      def populateRarePieces(
          pieces: mutable.SortedSet[PieceInfo],
          count: Int = 0): Unit = {
        if (count <= k && !pieces.isEmpty) {
          val pieceInfo = pieces.head
          if (possibles(pieceInfo.index)) {
            availablePiecesBuffer += pieceInfo.index
          }
          populateRarePieces(pieces.drop(1), count + 1)
        }
      }

      populateRarePieces(piecesSet)
      val availablePieces = availablePiecesBuffer.toArray
      val index = chosenPieces(
        (new Random).nextInt(
          rarePieceJitter min availablePieces.size))
      chosenPieces(index)

    }

  }

}

// This actor takes care of the downloading of a *single* torrent
class TorrentClient(fileName: String) extends Actor { this: ScheduleProvider =>

  import context.dispatcher
  import TorrentClient._

  // Constants
  val torrent = Torrent.fromFile(fileName)
  val unchokeFrequency: FiniteDuration = 10 seconds
  val optimisticUnchokeFrequency: FiniteDuration = 30 seconds
  val rarePieceJitter = 20

  // Spawn needed actor(s)
  val trackerClient = context.actorOf(TrackerClient.props)
  val server        = context.actorOf(PeerServer.props)
  val fileManager   = context.actorOf(FileManager.props(torrent))

  // peerId -> ActorRef
  val connectedPeers = mutable.Map.empty[ByteString, PeerConnection]

  // Of the connectedPeers, these are the peers that are interested / unchoked
  val communicatingPeers = mutable.Map.empty[ByteString, ActorRef]

  // SortedPieces that will sort piece indexes in order starting from rarest
  val pieces = new SortedPieces

  // This bitset holds which pieces are done
  var completedPieces = BitSet.empty

  // This bitset holds which pieces are currently being requested
  var requestedPieces = BitSet.empty

  // Frequency of all available pieces
  val allPiecesFreq = mutable.Map.empty[Int, Int].withDefaultValue(0)

  // Frequency of wanted Pieces hashed by index
  val wantedPiecesFreq = mutable.Map.empty[Int, Int].withDefaultValue(0)

  def receive = {
    case TorrentM.Start(t) =>
      startTorrent(t)

    case TrackerM.Response(r) =>
      connectPeers(r)

    case TorrentM.CreatePeer(connection, remote, peerId) =>
      val ip = remote.getHostString
      val port = remote.getPort
      val info = PeerInfo(peerId, Constant.ID.toByteString, torrent.infoHash, ip, port)
      val protocolProp = TorrentProtocol.props(connection)
      context.actorOf(Peer.props(info, protocolProp, fileManager))

    case TorrentM.Unchoke =>
      val unchokePeers = getMaxPeers(Constant.NumUnchokedPeers)
      connectedPeers foreach { case (peerId, peerConn =>
        if (!unchokePeers.contains(peerId)) { peerConn.peer ! BT.Choke }
      }
      unchokePeers foreach { case (peerId, peer) =>
        peer ! BT.Unchoke
      }
      scheduler.scheduleOnce(unchokeFrequency) {
        self ! TorrentM.Unchoke
      }

    case TorrentM.OptimisticUnchoke =>
      var chosenPeer: ByteString =
      scheduler.scheduleOnce(optimisticUnchokeFrequency) {
        self ! TorrentM.OptimisticUnchoke
      }
      // Todo implement optimistic unchoke

    case TorrentM.Available(update) =>
      update match {
        case Right(bitfield) =>
          bitfield foreach { i =>
            pieces.update(i, 1)
          }
        case Left(i) =>
          pieces.update(i, 1)
      }

    case TorrentM.DisconnectedPeer(peerId, peerHas) =>
      connectedPeers -= peerId
      communicatingPeers -= peerId
      peerHas foreach { i =>
        pieces.update(i, -1)
      }

    case TorrentM.PieceDone(i) =>
      completedPieces += i
      requestedPieces -= i
      broadcast(BT.Have(i))

    case PeerM.Connected(peerId) =>
      connectedPeers(peerId) = PeerConnection(peerId, sender, 0.0)
      sender ! BT.Bitfield(bitfield, torrent.numPieces)

    case PeerM.Ready(peerHas) =>
      val idx = pieces.rarest(peerHas, rarePieceJitter)
      sender ! PeerM.DownloadPiece(idx)
      requestedPieces += idx

    case PeerM.Downloaded(peerId, size) =>
      connectedPeers(peerId).rate += size
  }

  def startTorrent(fileName: String): Unit = {
    val request = Map[String, Any](
      "info_hash" -> urlEncode(torrent.infoHash.toChars),
      "peer_id" -> urlEncode(Constant.ID),
      "port" -> validTorrentPort,
      "uploaded" -> 0,
      "downloaded" -> 0,
      "compact" -> 0, // refuse compact responses for now
      "event" -> "started"
      // Add other params later
      )

    // Will get TrackerM.Response back
    trackerClient ! TrackerM.Request(torrent.announce, request)
  }

  // Create a peer actor per peer and start the download
  // ByteString is implicity converted to Int
  def connectPeers(response: String): Unit = {
    val resp = Bencode.decode(response.map(_.toByte).toIterator.buffered)
      .asInstanceOf[Map[String, Any]]
    if (resp contains "failure reason") {
      throw new Exception("Tracker response bad")
    }
    val numSeeders = resp("complete").asInstanceOf[Int]
    val numLeechers = resp("incomplete").asInstanceOf[Int]
    resp("peers") match {
      case prs: List[_] =>
        initConnectingPeers(prs.asInstanceOf[List[Map[String, Any]]])
      case _ =>
    }
  }

  // Open connection to peers listed in bencoded files
  // These peers will send a CreatePeer message to client when they're connected
  def initConnectingPeers(peers: List[Map[String, Any]]): Unit = {
    peers foreach { p =>
      val ip = p("ip").asInstanceOf[ByteString].toChars
      val port = p("port").asInstanceOf[Int]
      val peerId = p("peer id").asInstanceOf[ByteString]
      val remote = new InetSocketAddress(ip, port)
      context.actorOf(ConnectingPeer.props(remote, peerId))
    }
  }

  // Get valid int between 6881 to 6889 inclusive
  // TODO - Actually get available port
  def validTorrentPort: Int = {
    (new Random).nextInt(6889 - 6881 + 1) + 6881
  }

  def urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

  // https://wiki.theory.org/BitTorrentSpecification#Tracker_Response
  /*def peerListFromCompact(peers: ByteString): List[Peer] = {
    for {
      peersGrouped <- peers.grouped(6)
      peer <- peersGrouped
    } yield {
      val ip = peer.slice(0, 4).foldLeft("") { (acc, e) => acc + e.toChar }
      val port = peer.slice(4, 6)
      Peer()
    }
  }*/

  // Find top k contributors and unchoke those, while choking everyone else
  def getMaxPeers(k: Int): Map[ByteString, ActorRef] = {
    val maxK = new mutable.PriorityQueue[PeerConnection]()
    var minPeerRate = 0.0;
    connectedPeers foreach { case (id, peer) =>
      if (maxK.size == k) {
        if (peer.rate > minPeerRate) {
          maxK.dequeue
          maxK.enqueue(peer)
          minPeerRate = maxK.max.rate
        }
      } else {
        maxK.enqueue(peer)
        // Max actually returns the peerConnection with min rate due to the
        // inversion of priorities in the PeerConnection class
        minPeerRate = maxK.max.rate
      }
      peer.rate = 0.0
    }
    maxK.foldLeft(Map[ByteString, ActorRef]()) { (peers, peerConn) =>
      peers + (peerConn.id -> peerConn.peer)
    }
  }

  // Send message to ALL connected peers
  def broadcast(message: Any): Unit = {
    connectedPeers foreach { case (id, peerConn) => peerConn.peer ! message }
  }

  // Send message to currently communicating peers
  def communicate(message: Any): Unit = {
    communicatingPeers foreach { case (id, peer) => peer ! message }
  }

}