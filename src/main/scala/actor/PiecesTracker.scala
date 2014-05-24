package org.jerchung.torrent.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.util.ByteString
import org.jerchung.torrent.actor.message.{ TorrentM, TrackerM, BT, PeerM }
import scala.collection.BitSet
import scala.collection.mutable

object PiecesTracker {

  def props(numPieces: Int, pieceSize: Int, totalSize: Int): Props = {
    Props(new PiecesTracker(numPieces, pieceSize, totalSize) with ProdParent)
  }

  case class PieceInfo(index: Int, var count: Int)
      extends Ordered[PieceInfo] {
    def compare(pieceCount: PieceInfo): Int = {
      count.compare(pieceCount.count)
    }
  }

  object Message {
    case class ChoosePieceAndReport(possibles: BitSet, peer: ActorRef)
  }

}

/*
 * PiecesTracker keeps track of the frequence of each piece to be downloaded as
 * well as the state of each piece (downloaded, in progress, not downloaded).
 * This actor also is in charge of choosing the next piece for a peer to
 * download
 */
class PiecesTracker(numPieces: Int, pieceSize: Int, totalSize: Int)
    extends Actor { this: Parent =>

  // Set of pieces ordered by frequency starting from rarest
  val piecesSet = mutable.SortedSet[PieceInfo].empty

  // Map of piece index -> pieceInfo to provide access to pieces
  val piecesMap = mutable.Map[Int, PieceInfo].empty

  val RarePieceJitter = 20

  // Initialize with all pieces at 0 frequency
  override def preStart(): Unit = {
    (0 until numPieces) foreach { idx =>
      val piece = PieceInfo(idx, 0)
      add(piece)
    }
  }

  def receive = {

    // Choose a piece for the peer to download from the possibles, then report
    // back to TorrentClient which piece was chosen
    case ChoosePieceAndReport(possibles, peer) =>
      val idx = rarest(possibles)
      if (idx >= 0) {
        peer ! PeerM.DownloadPiece(idx)
        parent ! TorrentM.PieceRequested(idx)
      } else {
        peer ! BT.NotInterested
      }

    // Update frequency of pieces
    case TorrentM.Update(update) =>
      update match {
        case Right(bitfield) => bitfield foreach { i => update(i, 1) }
        case Left(i) => update(i, 1)
      }

    // Upon peer disconnect, update frequency of lost pieces
    case PeerM.Disconnected(peerId, peerHas) =>
      peerHas foreach { i => update(i, -1) }

  }

  def add(piece: PieceInfo): Unit = {
    piecesMap(piece.index) = piece
    piecesSet += piece
  }

  // Remove piece at index, and return the piece
  def remove(index: Int): PieceInfo = {
    val piece = piecesMap(index)
    piecesMap -= index
    piecesSet -= piece
    piece
  }

  def update(index: Int, count: Int): Unit = {
    val piece = remove(index)
    piece.count += count
    add(piece)
  }

  /* Perform the jittering of choosing from k of the rarest pieces whose
   * indexes are contained in possibles
   * Return the index of the chosen piece
   * Return -1 if no possibilities to choose
   */
  def rarest(possibles: BitSet, k: Int): Int = {
    val availablePiecesBuffer = mutable.ArrayBuffer[Int]()

    @tailrec
    def populateRarePieces(pieces: List[PieceInfo], count: Int = 0): Unit = {
      if (count <= k && !pieces.isEmpty) {
        val pieceInfo = pieces.head
        if (possibles(pieceInfo.index)) {
          availablePiecesBuffer += pieceInfo.index
        }
        populateRarePieces(pieces.tail, count + 1)
      }
    }

    populateRarePieces(piecesSet.toList)
    val availablePieces = availablePiecesBuffer.toArray
    if (availablePieces.isEmpty) {
      -1
    } else {
      val index = chosenPieces(
        (new Random).nextInt(
          k min availablePieces.size))
      chosenPieces(index)
    }
  }

}