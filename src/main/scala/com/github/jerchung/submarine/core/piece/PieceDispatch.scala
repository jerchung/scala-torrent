package com.github.jerchung.submarine.core.piece

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.EventStream
import com.github.jerchung.submarine.core.base.{Core, Torrent}
import com.github.jerchung.submarine.core.peer.Peer
import com.github.jerchung.submarine.core.setting.Constant

import scala.collection.BitSet
import scala.util.Random
import scala.concurrent.duration._

object PieceDispatch {
  def props(args: Args): Props = {
    Props(new PieceDispatch(args) with AppCake)
  }

  case class Args(torrent: Torrent,
                  torrentEvents: EventStream)

  trait Provider {
    def pieceDispatch(args: Args): ActorRef
  }

  trait AppProvider extends Provider { this: Core.AppProvider =>
    override def pieceDispatch(args: Args): ActorRef =
      context.actorOf(PieceDispatch.props(args))
  }

  trait Cake {
    def provider: PiecePipeline.Provider
  }

  trait AppCake extends Cake { this: PieceDispatch =>
    val provider = new Core.AppProvider(context) with PiecePipeline.AppProvider
  }
}

/**
  * Keep track of the frequency of each piece, when receiving notification that a peer is ready to download a piece,
  * choose a rare piece and tell that peer to start downloading
  * @param args required metadata to choose a piece
  */
class PieceDispatch(args: PieceDispatch.Args)
    extends Actor with ActorLogging { this: PieceDispatch.Cake =>

  lazy val RarePieceJitter = 20
  lazy val HighPriorityChance = 0.4f

  lazy val numPieces: Int = args.torrent.numPieces
  lazy val pieceSize: Int = args.torrent.pieceSize
  lazy val totalTorrentSize: Int = args.torrent.totalSize

  // Initialize all piece frequencies to 0
  var pieceFrequencies: Map[Int, Int] = (0 until numPieces).map(index => index -> 0).toMap

  var completedPieces = BitSet()
  var highPriorityPieces = BitSet()

  lazy val piecePipelines: Array[ActorRef] = initializePiecePipeline(numPieces)

  override def preStart(): Unit = {
    args.torrentEvents.subscribe(self, classOf[Peer.Announce])
    args.torrentEvents.subscribe(self, classOf[PiecePipeline.Announce])
  }

  def receive: Receive = handlePeerMessages orElse handlePiecePipelineMessages

  def handlePeerMessages: Receive = {
    case Peer.Announce(peer, publish) =>
      publish match {
        // Connected means that the handshake is complete, so we should send our bitfield
        case _: Peer.Connected =>
          peer ! Peer.Message.IHave(completedPieces)

        case Peer.Disconnected(_, _, peerHas) =>
          peerHas.foreach { index =>
            if (pieceFrequencies.getOrElse(index, 0) > 0) {
              pieceFrequencies += (index -> (pieceFrequencies(index) - 1))
            }
          }

        case Peer.Available(available) =>
          val update: BitSet = available match {
            case Left(index) => BitSet(index)
            case Right(peerHas) => peerHas
          }

          update.foreach { index =>
            if (pieceFrequencies.contains(index)) {
              pieceFrequencies += (index -> (pieceFrequencies(index) + 1))
            }
          }

          val interestingPieces: BitSet = update &~ completedPieces
          peer ! Peer.Message.Interested(interestingPieces.nonEmpty)

        case Peer.IsReady(peerHas) =>
          lazy val chance = Random.nextFloat()
          val highPriIntersect= highPriorityPieces & peerHas
          val possibles = if (highPriIntersect.nonEmpty && chance < HighPriorityChance)
            highPriIntersect
          else
            peerHas &~ completedPieces

          val rarePieces: Array[Int] = possibles
            .toList
            .filter(pieceFrequencies.getOrElse(_, 0) > 0)
            .sortBy(pieceFrequencies(_))
            .take(RarePieceJitter)
            .toArray

          val message: Peer.Message = if (rarePieces.isEmpty) {
            Peer.Message.Interested(false)
          } else {
            val index = rarePieces(Random.nextInt(rarePieces.length))
            Peer.Message.DownloadPiece(
              index,
              piecePipelines(index)
            )
          }

          peer ! message

        case _ => ()
      }
  }

  def handlePiecePipelineMessages: Receive = {
    case PiecePipeline.Done(index, _) =>
      completedPieces += index
      args.torrentEvents.publish(Peer.Message.IHave(completedPieces))

    case PiecePipeline.Priority(index, isHigh) =>
      if (isHigh) highPriorityPieces += index
      else highPriorityPieces -= index
  }

  def initializePiecePipeline(numPieces: Int): Array[ActorRef] = {
    val arr = new Array[ActorRef](numPieces)

    for (index <- 0 until numPieces) {
      val size = pieceSize.min(totalTorrentSize - (index * pieceSize))
      val pieceHash = args.torrent.indexedPieceHashes(index)
      val piecePipeline = provider.piecePipeline(PiecePipeline.Args(
        index,
        size,
        Constant.BlockSize,
        pieceHash,
        5,
        30.seconds,
        args.torrentEvents
      ))
      arr(index) = piecePipeline
    }

    arr
  }
}
