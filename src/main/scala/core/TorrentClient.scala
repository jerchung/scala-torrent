package storrent.core

import akka.actor.{Actor, ActorRef, Props, ActorLogging }
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
import storrent.tracker._
import storrent.peer._
import storrent.file._
import scala.annotation.tailrec
import scala.collection.BitSet
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

object TorrentClient {
  def props(config: Config): Props = {
    Props(new TorrentClient(config) with AppCake with Core.AppParent)
  }

  trait Cake { this: TorrentClient =>
    trait Provider {
      def fileManager(torrent: Torrent, folder: String): ActorRef
      def piecesManager(numPieces: Int, pieceSize: Int, totalSize: Int): ActorRef
      def peerRouter(fileManager: ActorRef, peersManager: ActorRef, piecesManager: ActorRef): ActorRef
      def peerServer(port: Int, router: ActorRef, manager: ActorRef): ActorRef
      def peersManager(state: ActorRef, torrent: Torrent): ActorRef
    }
    def provider: Provider
    def trackerClient: ActorRef
    def state: ActorRef
  }

  trait AppCake extends Cake { this: TorrentClient =>
    lazy val trackerClient = context.actorOf(TrackerClient.props)
    lazy val state = context.actorOf(PrintActor.props)
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
      def peerServer(port: Int, router: ActorRef, manager: ActorRef): ActorRef =
        context.actorOf(PeerServer.props(port, router, manager))
      def peersManager(state: ActorRef, torrent: Torrent): ActorRef =
        context.actorOf(PeersManager.props(state, torrent))
    }
  }
}

// This actor takes care of the downloading of a *single* torrent
class TorrentClient(config: Config) extends Actor with ActorLogging {
  this: TorrentClient.Cake with Core.Parent =>

  import context.dispatcher

  // Constants
  val torrent = Torrent.fromFile(config.torrentFile)

  // Spawn provider actors
  val fileManager = provider.fileManager(torrent, config.folderPath)
  val piecesManager = provider.piecesManager(
    torrent.numPieces,
    torrent.pieceSize,
    torrent.totalSize
  )
  val peersManager = provider.peersManager(state, torrent)
  val peerRouter = provider.peerRouter(fileManager, peersManager, piecesManager)

  override def preStart(): Unit = {
    self ! TorrentM.Start
  }

  // TorrentM message cases
  def receiveTorrentMessage: Receive = {
    case TorrentM.Start =>
      log.info(s"Starting torrent ${torrent.name}")
      startTorrent()

    case TrackerM.Response(r) =>
      val res = Bencode.decode(r.body.toList).asInstanceOf[Map[String, Any]]
      res.get("failure reason") match {
        case Some(reason) =>
          throw new TorrentError(reason.asInstanceOf[ByteString].toChars)
        case None =>
          provider.peerServer(config.port, peerRouter, peersManager)
          connectPeers(res)
      }
  }

  // Link default receives
  def receive = receiveTorrentMessage

  def startTorrent(): Unit = {
    logTorrentInfo()
    val trackerInfo = TrackerInfo(
      infoHash = torrent.infoHash,
      peerId = Constant.ID,
      port = config.port,
      uploaded = 0,
      downloaded = 0,
      left = 0,
      numWant = 10,
      compact = 1,
      event = "started"
    )

    // Will get TrackerM.Response back
    trackerClient ! TrackerM.Request(torrent.announce, trackerInfo)
  }

  def logTorrentInfo(): Unit = {
    log.info("General Torrent Info:")
    log.info(s"Torrent Name: ${torrent.name}")
    log.info(s"Total size: ${torrent.totalSize} bytes")
    log.info(s"Piece Size: ${torrent.pieceSize}")
    log.info(s"Number of pieces: ${torrent.numPieces}")
  }

  // Create a peer actor per peer and start the download
  def connectPeers(data: Map[String, Any]): Unit = {
    if (data.contains("failure reason")) {
      throw new Exception("Tracker response bad")
    }
    val numSeeders = data("complete").asInstanceOf[Int]
    val numLeechers = data("incomplete").asInstanceOf[Int]
    log.info(s"Peer info -- Seeders: $numSeeders, Peers: $numLeechers")
    val peers = data("peers") match {
      case prs: List[_] =>
        parsePeers(prs.asInstanceOf[List[Map[String, Any]]])
        // initConnectingPeers(prs.asInstanceOf[List[Map[String, Any]]])
      case prs: ByteString =>
        parseCompactPeers(prs)
    }

    log.debug(s"Peer INFO: $peers")

    peers.foreach { case (ip, port, peerId) =>
      log.info(s"Connecting to peer at ip: $ip, port: $port, peerId: $peerId")
      val id = ByteString(Constant.ID)
      val infoHash = ByteString.fromArray(torrent.infoHash)
      peersManager ! PeersManager.ConnectingPeer(ip, port, peerId, peerRouter)
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
}
