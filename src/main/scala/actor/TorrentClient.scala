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
import org.jerchung.torrent.Constant
import org.jerchung.torrent.Convert._
import org.jerchung.torrent.Torrent
import scala.collection.BitSet
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

// This case class encapsulates the information needed to create a peer actor
case class PeerInfo(
  peerId: Option[ByteString],
  ownId: ByteString,
  infoHash: ByteString,
  ip: String,
  port: Int
)

object TorrentClient {

  def props(fileName: String): Props = {
    Props(new TorrentClient(fileName: String) with ProdScheduler)
  }

  case class PeerConnection (id: ByteString, peer: ActorRef, var rate: Double)
      extends Ordered[PeerConnection] {

    // Want the minimum to be highest priority
    // When put into priority queue, max becomes min and vice versa
    def compare(that: PeerConnection): Int = {
      -1 * rate.compare(that.rate)
    }
  }

}

// This actor takes care of the downloading of a *single* torrent
class TorrentClient(fileName: String) extends Actor { this: ScheduleProvider =>

  import context.dispatcher
  import TorrentClient.PeerConnection

  // Constants
  val torrent = Torrent.fromFile(fileName)
  val unchokeFrequency: FiniteDuration = 10 seconds

  // Spawn needed actor(s)
  val trackerClient = context.actorOf(TrackerClient.props)
  val server        = context.actorOf(PeerServer.props)
  val fileManager   = context.actorOf(FileManager.props(torrent))

  // peerId -> ActorRef
  val connectedPeers = mutable.Map.empty[ByteString, PeerConnection]

  // Of the connectedPeers, these are the peers that are interested / unchoked
  val communicatingPeers = mutable.Map.empty[ByteString, ActorRef]

  // This bitset holds which pieces are done
  var bitfield = BitSet.empty

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
      val info = PeerInfo(peerId, Constant.ID.toByteString, torrent.hashedInfo, ip, port)
      val protocolProp = TorrentProtocol.props(connection)
      context.actorOf(Peer.props(info, protocolProp, fileManager))

    case TorrentM.UnchokePeers =>
      communicatingPeers = connectedPeers
      scheduler.scheduleOnce(unchokeFrequency) { self ! TorrentM.UnchokePeers }

    case TorrentM.Available(update) =>
      update match {
        case Right(bitfield) =>
          bitfield foreach { i =>
            allPiecesFreq(i) += 1
            wantedPiecesFreq(i) += 1
          }
        case Left(i) =>
          allPiecesFreq(i) += 1
          wantedPiecesFreq(i) += 1
      }

    case TorrentM.DisconnectedPeer(peerId, peerHas) =>
      connectedPeers -= peerId
      communicatingPeers -= peerId
      peerHas foreach { i =>
        allPiecesFreq(i) -= 1
        wantedPiecesFreq(i) -= 1
        if (wantedPiecesFreq(i) <= 0) { wantedPiecesFreq -= i } // Remove key
      }

    case TorrentM.PieceDone(i) =>
      bitfield += i
      wantedPiecesFreq -= i
      broadcast(BT.Have(i))

    case PeerM.Connected(peerId) =>
      connectedPeers(peerId) = PeerConnection(peerId, sender, 0.0)
      sender ! BT.Bitfield(bitfield, torrent.numPieces)

    case PeerM.Downloaded(peerId, size) =>
      connectedPeers(peerId).rate += size
  }

  def startTorrent(fileName: String): Unit = {
    val request = Map[String, Any](
      "info_hash" -> urlEncode(torrent.hashedInfo.toChars),
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

  def createPeer(connection, remote, peerId): Unit = {
    val ip = remote.getHostString
    val port = remote.getPort
    val info = PeerInfo(peerId, Constant.ID.toByteString, torrent.hashedInfo, ip, port)
    val protocolProp = TorrentProtocol.props(connection)
    context.actorOf(Peer.props(info, protocolProp, fileManager))
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

  // Find top K contributors and unchoke those, while choking everyone else
  def getMaxPeers: Map[ByteString, ActorRef] = {
    val maxK = new mutable.PriorityQueue[PeerConnection]()
    var minPeerRate = 0.0;
    connectedPeers foreach { case (id, peer) =>
      if (maxK.size == Constant.NumUnchokedPeers) {
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

  def scheduleUnchoke: Unit = {
    scheduler.scheduleOnce(unchokeFrequency) { self ! TorrentM.UnchokePeers }
  }

}