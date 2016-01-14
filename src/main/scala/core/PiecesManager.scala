package storrent.core

import akka.actor.PoisonPill
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.ByteString
import storrent.message.{ TorrentM, TrackerM, BT, PeerM }
import scala.annotation.tailrec
import scala.collection.BitSet
import scala.collection.mutable
import scala.collection.SortedSet
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random
import Core._

object PiecesManager {
  def props(numPieces: Int, pieceSize: Int, totalSize: Int, state: ActorRef): Props = {
    Props(new PiecesManager(numPieces, pieceSize, totalSize, state) with AppParent)
  }

  case class Progress(pieceCounts: Map[Int, Int], completedPieces: BitSet, inProgressPieces: BitSet)
}

/*
 * PiecesManager keeps track of the frequence of each piece.
 * This actor also is in charge of choosing the next piece for a peer to
 * download.
 */
class PiecesManager(numPieces: Int, pieceSize: Int, totalSize: Int, state: ActorRef)
    extends Actor with ActorLogging { this: Parent =>

  import context.dispatcher
  import PiecesManager._

  val RarePieceJitter = 20

  // Initialize all counts of pieces to 0
  // index -> count
  val pieceCounts: mutable.Map[Int, Int] =
    mutable.Map[Int, Int]((0 until numPieces).map { i => i -> 0 }.toSeq:_ *)

  var completedPieces = BitSet()
  var requestedPieces = BitSet()

  def receive = handle andThen update

  def update: Receive = {
    case _ => state ! Progress(pieceCounts.toMap, completedPieces, requestedPieces)
  }

  def handle: Receive = {
    // Choose a piece for the peer
    case PeerM.ReadyForPiece(peerHas) =>
      val peer = sender
      val inFlight = completedPieces | requestedPieces
      val possibles = peerHas &~ inFlight
      val idx = choosePiece(possibles)
      if (idx < 0) {
        log.debug("Sending NOT INTERESTED")
        peer ! BT.NotInterested
      } else {
        val size = pieceSize min (totalSize - (pieceSize * idx))
        peer ! PeerM.DownloadPiece(idx, size)
        requestedPieces += idx
      }

    // Update frequency of pieces
    case msg @ PeerM.PieceAvailable(available) =>
      val interested: Boolean = available match {
        case Right(bitfield) =>
          bitfield.foreach { i => pieceCounts(i) += 1 }
          val possibles = bitfield &~ (completedPieces | requestedPieces)
          possibles.nonEmpty
        case Left(i) =>
          pieceCounts(i) += 1
          !completedPieces.contains(i) && !requestedPieces.contains(i)
      }

      if (interested) { sender ! BT.Interested }
      // log.debug(s"Got piece availability $msg")
      // log.debug(s"Current availability state: $pieceCounts")

    // Upon peer disconnect, update frequency of lost pieces
    case PeerM.Disconnected(_, peerHas, _, _) =>
      peerHas foreach { i => pieceCounts(i) -= 1 }

    // Put piece back into pool for selection for peer download
    case PeerM.PieceAborted(idx) =>
      requestedPieces -= idx

    // Peer is connected, send a bitfield message
    case msg: PeerM.Connected =>
      sender ! BT.Bitfield(completedPieces, numPieces)

    case PeerM.PieceDone(idx) =>
      completedPieces += idx
      log.debug(s"${numPieces - completedPieces.size} pieces remaining")

    case PeerM.PieceInvalid(idx) =>
      requestedPieces -= idx
  }

  def choosePiece(possibles: BitSet): Int = {
    val jitterIndexes = possibles
                      .toArray
                      .sortBy(pieceCounts.getOrElse(_, 0))
                      .take(RarePieceJitter)
    if (jitterIndexes.isEmpty) {
      -1
    } else {
      val random = Random.nextInt(jitterIndexes.size)
      jitterIndexes(random)
    }
  }
}
