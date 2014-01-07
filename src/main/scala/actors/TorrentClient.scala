package org.jerchung.torrent

import akka.actor.{Actor, ActorRef, Props}
import org.jerchung.bencode.Bencode
import org.jerchung.bencode.Conversions._
import java.net.URLEncoder

// This actor takes care of the downloading of a *single* torrent

case class Peer(ip: String, port: Int, id: String = "")

class TorrentClient(peerId: String) extends Actor {

  // Spawn needed actors
  val trackerClient = context.actorOf[TrackerClient.props]
  val peerClient = context.actorOf[PeerClient.props]

  def receive = {
    case StartTorrent(t) => startTorrent(t)
    case TrackerResponse(r) => connectPeers(r)
  }

  def startTorrent(fileName: String): Unit = {
    val torrent = Torrent.fromFile(filename)
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

    // Will get TrackerResponse back
    trackerClient ! TrackerRequest(torrent.announce, request)
  }

  // Create a peer actor per peer and start the download
  def connectPeers(response: String): Unit = {
    val resp: Map[String, Any] = Bencode.decode(response.map(_.toByte).toIterator.buffered)
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
      case prs: ByteString => Peer.listFromCompact(prs)
    }

  }

  def validTorrentPort: Int = {
    new Random.nextInt(6889 - 6881 + 1) + 6881
  }

  def urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

  // https://wiki.theory.org/BitTorrentSpecification#Tracker_Response
  def peerListFromCompact(peers: ByteString): List[Peer] = {
    for {
      peersGrouped <- peers.grouped(6)
      peer <- peersGrouped
    } yield {
      val ip = peer.slice(0, 4).foldLeft("") { (acc, e) => acc + e.toChar }
      val port = peer.slice(4, 6)
      Peer()
    }
  }

}