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
import org.jerchung.bencode.Conversions._
import org.jerchung.torrent.Torrent
import scala.collection.BitSet
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

// This case class encapsulates the information needed to create a peer actor
case class PeerInfo(
  peerId: ByteString,
  ownId: ByteString,
  infoHash: ByteString
  ip: Int,
  port: Int
)

case class Peer(id: ByteString, ip: ByteString, port: Int)

// Encapsulate interesting connected peer info - Maybe dont use this
case class ConnectedPeer(actor: ActorRef, ip: String, port: Int)

// This actor takes care of the downloading of a *single* torrent
class TorrentClient(id: String, fileName: String) extends Actor {

  import context.{ dispatcher, system }

  implicit val timeout = Timeout(5 seconds)

  // Constants
  val torrent  = Torrent.fromFile(fileName)
  val ClientId = "ST"
  val Version  = "1000"
  val ID       = s"-${ClientId + Version}-${randomIntString(12)}"

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
    case TorrentM.CreatePeer(peerId, ip, port, protocol) =>
      val peer = context.actorOf(PeerClient.props(info, protocol, fileManager)) // Todo - Add parameters
      connectedPeers(peerId) = peer
      sender ! peer
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
      "peer_id" -> urlEncode(ID),
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
      case prs: List[_] => prs.map { peer =>
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
        case Tcp.Connected(remote, local) =>
          val connection = sender
          val protocol = context.actorOf(TorrentProtocol.props(connection))
          val peerActor = context.actorOf(PeerClient.props(
            peerId, torrent.infoHash, protocol))
          peerActor ! BT.Handshake(infoHash, peerId)
        case _ =>
      }
    }

  }

  def instantiatePeers(peers: List[Map[String, Any]]): Unit = {
    peers foreach { p =>
      val ip = p("ip").asInstanceOf[ByteString].toChars
      val port = p("port").asInstanceOf[Int]
      val peerId = p("peer id").asInstanceOf[ByteString]
      val remote = new InetSocketAddress(ip, port)
      val connectionF = IO(Tcp) ? Tcp.Connect(remote)
      connectionF onSuccess {
        case Tcp.Connected(remote, local) =>
          val connection = sender
          val protocol = context.actorOf(TorrentProtocol.props(connection))
          val info = PeerInfo(peerId, ID, torrent.hashedInfo, ip, port)
          val peerActorF = self ? TorrentM.CreatePeer(info, protocol)
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
    communicatingPeers foreach { case (id, peer) => peer ! message }
  }

}