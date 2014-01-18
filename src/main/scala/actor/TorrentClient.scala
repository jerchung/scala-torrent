package org.jerchung.torrent.actor

import ActorMessage.{ TorrentM, TrackerM }
import org.jerchung.torrent.Torrent
import akka.actor.{Actor, ActorRef, Props}
import akka.io.{ IO, Tcp }
import akka.pattern.ask
import akka.util.ByteString
import akka.util.Timeout
import java.net.InetSocketAddress
import java.net.URLEncoder
import org.jerchung.bencode.Bencode
import org.jerchung.bencode.Conversions._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

// This case class encapsulates the information needed to create a peer actor
case class PeerInfo(peerId: ByteString, ownId: ByteString, infoHash: ByteString, ownAvailable: BitSet)

// This actor takes care of the downloading of a *single* torrent
class TorrentClient(id: String, fileName: String) extends Actor {

  implicit val timeout = Timeout(5 seconds)

  // Constants
  val torrent = Torrent.fromFile(fileName)
  val ClientID = "ST"
  val Version = "1000"
  val ID = s"-${ClientId + Version}-${randomIntString(12)}"

  // Spawn needed actor(s)
  val trackerClient = context.actorOf(TrackerClient.props)
  val server = context.actorOf(PeerServer.props)

  val connectedPeers = mutable.Map[ByteString, ActorRef]()
  val blockManager = context.actorOf(BlockManager.props(torrent. ))

  // This bitset holds which pieces have been downloaded - starts at 0
  var downloaded = BitSet.empty

  // What the total availability from all connected peers is
  // Be careful with this since it's a var mutable
  var availability = mutable.BitSet.empty

  // Map[pieceIndex, count] to check for frequency for piece picking
  val wantedPiecesFreq = mutable.Map[Int, Int]().withDefaultValue(0)

  def receive = {
    case p: Props => sender ! context.actorOf(p) // Actor creation
    case TorrentM.Start(t) => startTorrent(t)
    case TrackerM.Response(r) => connectPeers(r)
    case CreatePeer(peerId, infoHash, protocol) =>
      val peer = context.actorOf(PeerClient.props) // Todo - Add parameters
      connectedPeers(peerId) => peer
      sender ! peer
    case Available(update) =>
      update match {
        case Right(bitfield) =>
          availability |= bitfield
          bitfield foreach { i => wantedPiecesFreq(i) += 1 }
        case Left(i) =>
          availability += i
          wantedPiecesFreq(i) += 1
      }
    case Unavailable(remove) =>
      remove match {
        case Right(bitfield) =>
          availability --= bitfield
          bitfield foreach { i =>
            wantedPiecesFreq(i) -= 1
            if (wantedPiecesFreq(i) <= 0) { wantedPiecesFreq -= i} // Remove key
          }
        case Left (i) =>
          availability -= i
          wantedPiecesFreq(i) -= 1
          if (wantedPiecesFreq(i) <= 0) { wantedPiecesFreq -= i}
      }
    case BlockDownloaded(i) =>
      downloaded += i
      wantedPiecesFreq -= i
      broadcast(BT.Have(i))
  }

  def startTorrent(fileName: String): Unit = {
    val request = Map[String, Any](
      "info_hash" -> urlEncode(torrent.infoHash),
      "peer_id" -> urlEncode(peerId),
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
    val peers: List[Peer] = resp("peers") match {
      case prs: List => prs.map { peer =>
        val p = peer.asInstanceOf[Map[String, Any]]
        Peer(
          ip = p("ip").asInstanceOf[ByteString],
          port = p("port").asInstanceOf[Int],
          id = p("peer id").asInstanceOf[ByteString]
        )
      }
      // case prs: ByteString => peerlistFromCompact(prs)
      case _ => List()
    }
    peers foreach { peer =>
      val remote = new InetSocketAddress(peer.ip, peer.port)
      val connectionF = IO(Tcp) ? Tcp.Connect(remote)
      connectionF onSuccess {
        case Tcp.Conected(remote, local) =>
          val connection = sender
          val protocol =
          val peerActor = context.actorOf(PeerClient.props(
            peerId, torrent.infoHash, connection))
          peerActor ! BT.Handshake(infoHash, peerId)
      }
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
      random.nextInt(10).toChar + randomIntString(random, length - 1)
    } else {
      ""
    }
  }

  def broadcast(message: Message): Unit = {
    connectedPeers foreach { (id, peer) => peer ! message }
  }

}