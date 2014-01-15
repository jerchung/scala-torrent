package org.jerchung.torrent

import ActorMessage.{ TorrentM, TrackerM }
import akka.actor.{Actor, ActorRef, Props}
import akka.io.{ IO, Tcp }
import akka.pattern.ask
import akka.util.ByteString
import akka.util.Timeout
import java.net.InetSocketAddress
import java.net.URLEncoder
import org.jerchung.bencode.Bencode
import org.jerchung.bencode.Conversions._
import scala.concurrent.duration._
import scala.util.Random

// This case class encapsulates the information needed to create a peer actor
case class Peer(peerId: ByteString, ownId: ByteString, infoHash: ByteString)

// This actor takes care of the downloading of a *single* torrent
class TorrentClient(id: String, fileName: String) extends Actor {

  implicit val timeout = Timeout(5 seconds)

  // Get that torrent file
  val torrent = Torrent.fromFile(fileName)

  // Spawn needed actor(s)
  val trackerClient = context.actorOf(TrackerClient.props)
  val server = context.actorOf(PeerServer.props(id))

  // This array holds which pieces have been downloaded - starts at 0
  val available = Array.fill[Byte](torrent.pieces.length / 20)(0)

  def receive = {
    case p: Props => sender ! context.actorOf(p) // Actor creation
    case TorrentM.Start(t) => startTorrent(t)
    case TrackerM.Response(r) => connectPeers(r)
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
      case prs: List[_] => prs.map { peer =>
        val p = peer.asInstanceOf[Map[String, Any]]
        Peer(
          ip = p("ip").asInstanceOf[ByteString],
          port = p("port").asInstanceOf[Int],
          id = p("peer id").asInstanceOf[ByteString]
        )
      }
      // case prs: ByteString => peerlistFromCompact(prs)
    }
    peers foreach { peer =>
      val remote = new InetSocketAddress(peer.ip, peer.port)
      val connectionF = IO(Tcp) ? Tcp.Connect(remote)
      connectionF map {
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

}