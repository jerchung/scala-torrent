package storrent.core

import akka.actor.{Actor, ActorRef, Props}
import akka.io.{ IO, Tcp }
import akka.pattern.ask
import akka.util.ByteString
import akka.util.Timeout
import java.net.InetSocketAddress
import java.net.URLEncoder
import storrent.message.{ TorrentM, TrackerM, BT, PeerM }
import storrent.bencode.Bencode
import storrent.Constant
import storrent.Convert._
import storrent.Torrent
import storrent.TorrentError
import storrent.peer._
import storrent.file._
import storrent.report.CurrentState
import scala.annotation.tailrec
import scala.collection.BitSet
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

object TorrentClient {

  def props(fileName: String, folder: String): Props = {
    Props(new TorrentClient(fileName: String, folder: String) with AppCake with Core.AppParent)
  }

  trait Cake { this: TorrentClient =>
    trait Provider {
      def fileManager(torrent: Torrent, folder: String): ActorRef
      def piecesManager(numPieces: Int, pieceSize: Int, totalSize: Int): ActorRef
      def peerRouter(fileManager: ActorRef, peersManager: ActorRef, piecesManager: ActorRef): ActorRef
      def connectingPeer(peerInfo: PeerInfo): ActorRef
    }
    def provider: Provider
    def trackerClient: ActorRef
    // def server: ActorRef
    def peersManager: ActorRef
    def state: ActorRef
  }

  trait AppCake extends Cake { this: TorrentClient =>
    lazy val trackerClient = context.actorOf(TrackerClient.props)
    lazy val state = context.actorOf(CurrentState.props)
  //   lazy val server = context.actorOf(PeerServer.props)
    lazy val peersManager = context.actorOf(PeersManager.props(state))
    override object provider extends Provider {
      def fileManager(torrent: Torrent, folder: String) = context.actorOf(FileManager.props(torrent, folder))
      def piecesManager(numPieces: Int, pieceSize: Int, totalSize: Int) =
        context.actorOf(PiecesManager.props(
          numPieces,
          pieceSize,
          totalSize,
          state
        ))
      def peerRouter(fileManager: ActorRef, peersManager: ActorRef, piecesManager: ActorRef) =
        context.actorOf(PeerRouter.props(
          fileManager,
          peersManager,
          piecesManager
        ))
      def connectingPeer(peerInfo: PeerInfo) =
        context.actorOf(ConnectingPeer.props(peerInfo))
      def peer(info: PeerInfo, connection: ActorRef, router: ActorRef) =
        context.actorOf(Peer.props(info, connection, router))
    }
  }
}

// This actor takes care of the downloading of a *single* torrent
class TorrentClient(fileName: String, folder: String) extends Actor {
  this: TorrentClient.Cake with Core.Parent =>

  import context.dispatcher

  // Constants
  val torrent = Torrent.fromFile(fileName)
  println(torrent.pieceSize)

  // Spawn provider actors
  val fileManager = provider.fileManager(torrent, folder)
  val piecesManager = provider.piecesManager(torrent.numPieces, torrent.pieceSize, torrent.totalSize)

  val printActor = context.actorOf(Props(new PrintActor))
  val peerRouter = context.actorOf(PeerRouter.props(fileManager, peersManager, piecesManager))

  // TorrentM message cases
  def receiveTorrentMessage: Receive = {

    case TorrentM.Start(t) =>
      startTorrent(t)

    case TrackerM.Response(r) =>
      val res = Bencode.decode(r.body.toList).asInstanceOf[Map[String, Any]]
      res.get("failure reason") match {
        case Some(reason) => throw new TorrentError(reason.asInstanceOf[ByteString].toChars)
        case None => connectPeers(res)
      }

    case TorrentM.CreatePeer(connection, peerInfo) =>
      context.actorOf(Props(new Peer(peerInfo, connection, peerRouter) with Peer.AppCake))

  }

  // Link default receives
  def receive = receiveTorrentMessage

  def startTorrent(fileName: String): Unit = {
    val params = Map[String, String](
      "info_hash" -> urlBinaryEncode(torrent.infoHash.toArray),
      "peer_id" -> Constant.ID,
      "port" -> validTorrentPort.toString,
      "uploaded" -> "0",
      "downloaded" -> "0",
      "left" -> "0",
      "numwant" -> "5",
      "compact" -> "1",
      "event" -> "started"
    )

    // Will get TrackerM.Response back
    trackerClient ! TrackerM.Request(torrent.announce, params)
  }

  // Create a peer actor per peer and start the download
  def connectPeers(data: Map[String, Any]): Unit = {
    if (data.contains("failure reason")) {
      throw new Exception("Tracker response bad")
    }
    val numSeeders = data("complete").asInstanceOf[Int]
    val numLeechers = data("incomplete").asInstanceOf[Int]
    val peers = data("peers") match {
      case prs: List[_] =>
        parsePeers(prs.asInstanceOf[List[Map[String, Any]]])
        // initConnectingPeers(prs.asInstanceOf[List[Map[String, Any]]])
      case prs: ByteString =>
        parseCompactPeers(prs)
    }
    peers.foreach { case (ip, port, peerId) =>
      val id = ByteString(Constant.ID)
      val infoHash = ByteString.fromArray(torrent.infoHash)
      provider.connectingPeer(PeerInfo(peerId, id, infoHash, ip, port, torrent.numPieces, InitHandshake))
    }
  }

  def parseCompactPeers(peers: ByteString): List[(String, Int, Option[ByteString])] = {
    peers.grouped(6).toList.map  { case bytes =>
      val (ipBytes, portBytes) = bytes.splitAt(4)
      val ip = ipBytes.map(b => b & 0xFF).mkString(".")
      val port = portBytes.toInt
      (ip, port, None)
    }
  }

  def parsePeers(peers: List[Map[String, Any]]): List[(String, Int, Option[ByteString])] = {
    peers.map { p =>
      val ip = p("ip").asInstanceOf[ByteString].toChars
      val port = p("port").asInstanceOf[Int]
      val peerId = p("peer id").asInstanceOf[ByteString]
      (ip ,port, Some(peerId))
    }
  }

  // Get valid int between 6881 to 6889 inclusive
  // TODO - Actually get available port
  def validTorrentPort: Int = {
    (new Random).nextInt(6889 - 6881 + 1) + 6881
  }

  // Encode all bytes in %nn format (% prepended hex form)
  def urlBinaryEncode(bytes: Array[Byte]): String = {
    bytes.map("%" + "%02X".format(_)).mkString
  }
}
