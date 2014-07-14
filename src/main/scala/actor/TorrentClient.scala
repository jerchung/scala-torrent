package org.jerchung.torrent.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.io.{ IO, Tcp }
import akka.pattern.ask
import akka.util.ByteString
import akka.util.Timeout
import java.net.InetSocketAddress
import java.net.URLEncoder
import com.escalatesoft.subcut.inject._
import org.jerchung.torrent.actor.message.{ TorrentM, TrackerM, BT, PeerM }
import org.jerchung.torrent.actor.Peer.PeerInfo
import org.jerchung.torrent.bencode.Bencode
import org.jerchung.torrent.Constant
import org.jerchung.torrent.Convert._
import org.jerchung.torrent.dependency.BindingKeys
import org.jerchung.torrent.Torrent
import scala.annotation.tailrec
import scala.collection.BitSet
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

object TorrentClient {

  def props(fileName: String)(implicit bindingModule: BindingModule): Props = {
    Props(new TorrentClient(fileName: String))
  }

}

// This actor takes care of the downloading of a *single* torrent
class TorrentClient(fileName: String) extends Actor with AutoInjectable {

  import context.dispatcher
  import BindingKeys._

  // Constants
  val torrent = Torrent.fromFile(fileName)

  // Spawn needed actor(s) with dependency injection
  val trackerClient = injectOptional [ActorRef](TrackerClientId) getOrElse {
    context.actorOf(TrackerClient.props)
  }

  val server = injectOptional [ActorRef](PeerServerId) getOrElse {
    context.actorOf(PeerServer.props)
  }

  val fileManager = injectOptional [ActorRef](FileManagerId) getOrElse {
    context.actorOf(FileManager.props(torrent))
  }

  val peersManager = injectOptional [ActorRef](PeersManagerId) getOrElse {
    context.actorOf(PeersManager.props)
  }

  val piecesManager = injectOptional [ActorRef](PiecesManagerId) getOrElse {
    context.actorOf(
      PiecesManager.props(
        torrent.numPieces,
        torrent.pieceSize,
        torrent.totalSize
    ))
  }

  val parent = injectOptional [ActorRef](ParentId) getOrElse {
    context.parent
  }

  // TorrentM message cases
  def receiveTorrentMessage: Receive = {

    case TorrentM.Start(t) =>
      startTorrent(t)

    case TorrentM.TrackerR(r) =>
      connectPeers(r)

    case TorrentM.CreatePeer(connection, remote, peerId) =>
      val ip = remote.getHostString
      val port = remote.getPort
      val info = PeerInfo(peerId, Constant.ID.toByteString, torrent.infoHash, ip, port)
      val peerRouter = context.actorOf(PeerRouter.props(
        fileManager,
        peersManager,
        piecesManager
      ))
      val protocolProp = TorrentProtocol.props(connection)
      context.actorOf(Peer.props(info, protocolProp, peerRouter))

  }

  // Link default receives
  def receive = receiveTorrentMessage

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

}