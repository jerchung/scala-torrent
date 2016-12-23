package com.github.jerchung.submarine.core.base

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.EventStream
import akka.util.ByteString
import com.github.jerchung.submarine.core.bencode.Bencode
import com.github.jerchung.submarine.core.peer.{ConnectedPeers, ConnectingPeer, Peer, PeerServer}
import com.github.jerchung.submarine.core.piece.{PieceDispatch, Pieces}
import com.github.jerchung.submarine.core.setting.Constant
import com.github.jerchung.submarine.core.tracker.Tracker


object TorrentClient {
  def props(torrent: Torrent, dest: String, port: Int): Props = {
    Props(new TorrentClient(torrent, dest, port) with AppCake with Core.AppParent)
  }

  trait Cake extends Core.Cake { this: TorrentClient =>
    trait Provider extends Core.Cake#Provider
                   with Peer.Provider
                   with ConnectedPeers.Provider
                   with Pieces.Provider
                   with ConnectingPeer.Provider
                   with PeerServer.Provider
                   with PieceDispatch.Provider
                   with Announce.Provider
                   with Tracker.Provider

    def provider: Provider
  }

  trait AppCake extends Cake { this: TorrentClient =>
    val provider = new Provider with Peer.AppProvider
                                with ConnectedPeers.AppProvider
                                with Pieces.AppProvider
                                with ConnectingPeer.AppProvider
                                with PeerServer.AppProvider
                                with PieceDispatch.AppProvider
                                with Announce.AppProvider
                                with Tracker.AppProvider
  }

  case object Start
  case class CoreActors(piecesDispatch: ActorRef,
                        piecesPersist: ActorRef,
                        connectedPeers: ActorRef)
}

// This actor takes care of the downloading of a *single* torrent
class TorrentClient(torrent: Torrent, dest: String, port: Int) extends Actor with ActorLogging {
  this: TorrentClient.Cake with Core.Parent =>

  val torrentEvents: EventStream = new EventStream(context.system)

  val tracker = provider.tracker
  val fileManager = provider.fileManager(torrent, dest)
  val piecesManager = provider.pieceFrequency(
    torrent.numPieces,
    torrent.pieceSize,
    torrent.totalSize
  )
  val peersManager = provider.connectedPeers()
  val peerRouter = provider.announce(fileManager, peersManager, piecesManager)

  override def preStart(): Unit = {


    self ! TorrentClient.Start
  }

  def resuming: Receive = {
    case ResumeSync.Resumed =>

  }

  // TorrentM message cases
  def receiveTorrentMessage: Receive = {
    case TorrentClient.Start =>
      log.info(s"Starting torrent ${torrent.name}")
      startTorrent()

    case Tracker.Response(body) =>
      val trackerResponse = Bencode.decode(body)
      trackerResponse.get("failure reason") match {
        case Some(reason) =>
          throw new TorrentError(reason.asInstanceOf[ByteString].toChars)
        case None =>
          provider.peerServer(port)
          connectPeers(trackerResponse)
      }
    case TorrentClient.ConnectPeer(connection, peerId, ip, peerPort, state) =>
      val peerConfig = Peer.Config(
        peerId,
        ByteString(Constant.ID),
        ByteString.fromArray(torrent.infoHash),
        ip,
        peerPort,
        torrent.numPieces,
        state
      )
      provider.peer(peerConfig, connection, peerRouter)
  }

  // Link default receives
  def receive = receiveTorrentMessage

  def startTorrent(): Unit = {
    logTorrentInfo()
    val params = Map[String, String](
      "info_hash" -> urlBinaryEncode(torrent.infoHash.toArray),
      "peer_id" -> Constant.ID,
      "port" -> port.toString,
      "uploaded" -> "0",
      "downloaded" -> "0",
      "left" -> "0",
      "numwant" -> "10",
      "compact" -> "1",
      "event" -> "started"
    )

    tracker ! Tracker.Request(torrent.announce, params)
  }

  def logTorrentInfo(): Unit = {
    log.info("General Torrent Info:")
    log.info(s"Torrent Name: ${torrent.name}")
    log.info(s"Total size: ${torrent.totalSize} bytes")
    log.info(s"Piece Size: ${torrent.pieceSize}")
    log.info(s"Number of pieces: ${torrent.numPieces}")
  }

  // Create a com.github.jerchung.submarine.core.peer actor per com.github.jerchung.submarine.core.peer and start the download
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
      log.info(s"Connecting to com.github.jerchung.submarine.core.peer at ip: $ip, port: $port, peerId: $peerId")
      val id = ByteString(Constant.ID)
      val infoHash = ByteString.fromArray(torrent.infoHash)
      provider.connectingPeer(ip, port, peerId)
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

  // Encode all bytes in %nn format (% prepended hex form)
  def urlBinaryEncode(bytes: Array[Byte]): String = {
    bytes.map("%" + "%02X".format(_)).mkString
  }
}
