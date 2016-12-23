package com.github.jerchung.submarine.core.piece

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.EventStream
import com.github.jerchung.submarine.core.base.Core.{AppParent, MessageBus, Parent}
import com.github.jerchung.submarine.core.base.{Core, Torrent}
import com.github.jerchung.submarine.core.peer.Peer
import com.github.jerchung.submarine.core.setting.Constant

import scala.collection.BitSet
import scala.util.Random

object PieceDispatch {
  def props(args: Args): Props = {
    Props(new PieceDispatch(args) with AppParent)
  }

  case class Args(torrent: Torrent,
                  torrentEvents: EventStream) extends MessageBus

  trait Provider extends Core.Cake#Provider {
    def pieceFrequency(args: Args): ActorRef
  }

  trait AppProvider extends Provider {
    override def pieceFrequency(args: Args): ActorRef =
      context.actorOf(PieceDispatch.props(args))
  }
}

/**
  * Keep track of the frequency of each piece, when receiving notification that a peer is ready to download a piece,
  * choose a rare piece and tell that peer to start downloading
  * @param args required metadata to choose a piece
  */
class PieceDispatch(args: PieceDispatch.Args)
    extends Actor with ActorLogging { this: Parent =>

  val RarePieceJitter = 20
  val HighPriorityChance = 0.4f

  val numPieces: Int = args.torrent.numPieces
  val pieceSize: Int = args.torrent.pieceSize
  val totalTorrentSize: Int = args.torrent.totalSize

  // Initialize all piece frequencies to 0
  var pieceFrequencies: Map[Int, Int] = (0 until numPieces).map(index => index -> 0).toMap

  var completedPieces = BitSet()
  var highPriorityPieces = BitSet()

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

        case Peer.Disconnected(_, peerHas) =>
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

          val interestingPieces: BitSet = update &~ (completedPieces | requestedPieces)

          peer ! Peer.Message.Interested(interestingPieces.nonEmpty)

        case Peer.PieceDone(index, _) =>
          requestedPieces -= index
          completedPieces += index

          args.torrentEvents.publish(Peer.Message.IHave(completedPieces))

        case Peer.PieceInvalid(index) =>
          requestedPieces -= index

        case Peer.ChokedOnPiece(index) =>
          requestedPieces -= index

        case Peer.IsReady(peerHas) =>
          val highPriIntersect = highPriorityPieces & peerHas
          if (highPriIntersect.nonEmpty && Random.nextFloat < HighPriorityChance) {

          }

          val possibles = peerHas &~ (completedPieces | requestedPieces)

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
            val pieceHash = args.torrent.indexedPieceHashes(index)

            requestedPieces += index

            Peer.Message.DownloadPiece(
              index,
              pieceSize.min(totalTorrentSize - (index * pieceSize)),
              Constant.BlockSize,
              pieceHash
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
      if (isHigh)
        highPriorityPieces += index
      else
        highPriorityPieces -= index

  }
}
