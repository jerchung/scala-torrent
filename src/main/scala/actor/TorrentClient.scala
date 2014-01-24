package org.jerchung.torrent.actor

import org.jerchung.torrent.actor.message.{ TorrentM, TrackerM, BT }
import akka.actor.{Actor, ActorRef, Props}
import akka.io.{ IO, Tcp }
import akka.pattern.ask
import akka.util.ByteString
import akka.util.Timeout
import java.net.InetSocketAddress
import java.net.URLEncoder
import org.jerchung.bencode.Bencode
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

// This actor takes care of the downloading of a *single* torrent
class TorrentClient(id: String, fileName: String) extends Actor {

  import context.{ dispatcher, system }

  // Constants
  val torrent  = Torrent.fromFile(fileName)
  val ClientId = "ST"
  val Version  = "1000"
  val ID       = ByteString.fromString(s"-${ClientId + Version}-${randomIntString(12)}")

  // Spawn needed actor(s)
  val trackerClient = context.actorOf(TrackerClient.props)
  val server        = context.actorOf(PeerServer.props)
  val fileManager   = context.actorOf(FileManager.props(torrent))

  // peerId -> ActorRef
  val connectedPeers = mutable.Map.empty[ByteString, ActorRef]

  // Of the connectedPeers, these are the peers that are interested / unchoked
  val communicatingPeers = mutable.Map.empty[ByteString, ActorRef]

  // This bitset holds which pieces are done
  var piecesDone = BitSet.empty

  // Frequency of all available pieces
  val allPiecesFreq = mutable.Map.empty[Int, Int].withDefaultValue(0)

  // Frequency of wanted Pieces hashed by index
  val wantedPiecesFreq = mutable.Map.empty[Int, Int].withDefaultValue(0)

  def receive = {
    case p: Props => sender ! context.actorOf(p) // Actor creation
    case TorrentM.Start(t) => startTorrent(t)
    case TrackerM.Response(r) => connectPeers(r)
    case TorrentM.Register(peerId) => connectedPeers(peerId) = sender
    case TorrentM.CreatePeer(connection, remote, peerId) =>
      val ip = remote.getHostString
      val port = remote.getPort
      val info = PeerInfo(peerId, ID, torrent.hashedInfo, ip, port)
      val peer = context.actorOf(Peer.props(info, connection, fileManager))
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
      peerHas foreach { i =>
        allPiecesFreq(i) -= 1
        wantedPiecesFreq(i) -= 1
        if (wantedPiecesFreq(i) <= 0) { wantedPiecesFreq -= i} // Remove key
      }
    case TorrentM.PieceDone(i) =>
      piecesDone += i
      wantedPiecesFreq -= i
      broadcast(BT.Have(i))
  }

  def startTorrent(fileName: String): Unit = {
    val request = Map[String, Any](
      "info_hash" -> urlEncode(torrent.hashedInfo.toChars),
      "peer_id" -> urlEncode(ID.toChars),
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
      case prs: List[_] => instantiatePeers(prs.asInstanceOf[List[Map[String, Any]]])
      case _ =>
    }
  }

  def instantiatePeers(peers: List[Map[String, Any]]): Unit = {
    peers foreach { p =>
      val ip = p("ip").asInstanceOf[ByteString].toChars
      val port = p("port").asInstanceOf[Int]
      val peerId = p("peer id").asInstanceOf[ByteString]
      val remote = new InetSocketAddress(ip, port)
      context.actorOf(ConnectingPeer.props(remote, peerId))
    }
  }

  def connectedPeer: Unit = {

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

  def randomIntString(length: Int, random: Random = new Random): String = {
    if (length > 0) {
      random.nextInt(10).toChar + randomIntString(length - 1, random)
    } else {
      ""
    }
  }

  def broadcast(message: Any): Unit = {
    communicatingPeers foreach { case (id, peer) => peer ! message }
  }

}