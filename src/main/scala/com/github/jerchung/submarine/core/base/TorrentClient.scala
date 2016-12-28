package com.github.jerchung.submarine.core.base

import java.nio.file.Paths

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.EventStream
import akka.util.ByteString
import com.github.jerchung.submarine.core.peer._
import com.github.jerchung.submarine.core.persist.FileReaderWriter
import com.github.jerchung.submarine.core.piece.{PieceDispatch, Pieces}
import com.github.jerchung.submarine.core.state.TorrentState
import com.github.jerchung.submarine.core.tracker.Tracker


object TorrentClient {
  def props(args: Args): Props = {
    Props(new TorrentClient(args) with AppCake)
  }

  trait Cake { this: TorrentClient =>
    def provider: Peer.Provider
      with ConnectedPeers.Provider
      with Pieces.Provider
      with PeersGatekeeper.Provider
      with PieceDispatch.Provider
      with Tracker.Provider
      with TorrentCoordinator.Provider
      with TorrentState.Provider
      with FileReaderWriter.Provider

  }

  trait AppCake extends Cake { this: TorrentClient =>
    val provider = new Core.AppProvider(context)
      with Peer.AppProvider
      with ConnectedPeers.AppProvider
      with Pieces.AppProvider
      with PeersGatekeeper.AppProvider
      with PieceDispatch.AppProvider
      with Tracker.AppProvider
      with TorrentCoordinator.AppProvider
      with TorrentState.AppProvider
      with FileReaderWriter.AppProvider
  }

  case object Start

  case class Args(torrent: Torrent,
                  port: Int,
                  path: String,
                  clientId: ByteString,
                  torrentId: Int)
}

// This actor takes care of the downloading of a *single* torrent
class TorrentClient(args: TorrentClient.Args) extends Actor with ActorLogging {
  this: TorrentClient.Cake =>

  val torrentEvents: EventStream = new EventStream(context.system)
  var tracker: ActorRef = _
  var peersGatekeeper: ActorRef = _
  var pieces: ActorRef = _
  var dispatch: ActorRef = _
  var torrentState: ActorRef = _
  var coordinator: ActorRef = _

  override def preStart(): Unit = {
    init()
    self ! TorrentClient.Start
  }

  // Link default receives
  def receive: Receive = {
    case TorrentClient.Start =>
      log.info(s"Starting torrent ${args.torrent.name}")
      startTorrent()
  }

  def startTorrent(): Unit = {
    logTorrentInfo()
    coordinator ! TorrentCoordinator.Initiate
  }

  def logTorrentInfo(): Unit = {
    log.info("General Torrent Info:")
    log.info(s"Torrent Name: ${args.torrent.name}")
    log.info(s"Total size: ${args.torrent.totalSize} bytes")
    log.info(s"Piece Size: ${args.torrent.pieceSize}")
    log.info(s"Number of pieces: ${args.torrent.numPieces}")
  }

  /**
    * Init all the actors after object initialization in preStart() because we need the TorrentClient.Cake trait to be
    * brought in successfully so the provider object is provided
    */
  private def init(): Unit = {
    val fileReaderWriter = {
      val fullPath = Paths.get(args.path, args.torrent.name).toString
      args.torrent.fileMode match {
        case Torrent.FileMode.Single => provider.singleFile(fullPath, args.torrent.totalSize)
        case Torrent.FileMode.Multiple => provider.multiFile(args.torrent.files, fullPath, args.torrent.totalSize)
      }
    }

    tracker = provider.tracker(Tracker.Args(torrentEvents))
    peersGatekeeper = provider.peersGatekeeper(PeersGatekeeper.Args(
      args.torrent,
      args.clientId,
      torrentEvents
    ))
    pieces = provider.pieces(Pieces.Args(
      args.torrent,
      args.path,
      50000,
      fileReaderWriter,
      torrentEvents
    ))
    dispatch = provider.pieceDispatch(PieceDispatch.Args(
      args.torrent,
      torrentEvents
    ))
    torrentState = provider.torrentState(TorrentState.Args(
      args.torrentId,
      args.torrent,
      torrentEvents
    ))
    coordinator = provider.torrentCoordinator(TorrentCoordinator.Args(
      args.torrent,
      args.port,
      tracker,
      pieces,
      dispatch,
      torrentState,
      torrentEvents
    ))
  }
}
