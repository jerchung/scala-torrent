package org.jerchung.torrent.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.util.ByteString
import org.jerchung.torrent.actor.message.{ TorrentM, TrackerM, BT, PeerM }
import scala.annotation.tailrec
import scala.collection.BitSet
import scala.collection.SortedSet
import scala.concurrent.Future
import ExecutionContext.Implicits.global
import scala.util.Random

object PiecesTracker {

  def props(numPieces: Int, pieceSize: Int, totalSize: Int): Props = {
    Props(new PiecesTracker(numPieces, pieceSize, totalSize) with ProdParent)
  }

  case class PieceInfo(index: Int, var count: Int)
      extends Ordered[PieceInfo] {
    def compare(pieceCount: PieceInfo): Int = {
      count.compare(pieceCount.count)
    }

    def hashCode(): Int = {
      index
    }
  }

  object Message {
    case class ChoosePieceAndReport(possibles: BitSet, peer: ActorRef)
  }

}

/*
 * PiecesTracker keeps track of the frequence of each piece.
 * This actor also is in charge of choosing the next piece for a peer to
 * download.
 * This actor also keeps track of currently choked peers.  If a peer was choked
 * in the middle of a download and the piece is left unfinished, if that peer
 * is unchoked before that piece is chosen for download by another peer, then it
 * can resume download of the piece.  Otherwise, the piece download on that peer
 * will be resetted.
 */
class PiecesTracker(numPieces: Int, pieceSize: Int, totalSize: Int)
    extends Actor { this: Parent =>

  import PiecesTracker.Message.ChoosePieceAndReport
  import PiecesTracker.PieceInfo

  // Set of pieces ordered by frequency starting from rarest
  var piecesSet = SortedSet[PieceInfo]()

  // Map of piece index -> pieceInfo to provide access to pieces
  var piecesMap = Map[Int, PieceInfo]()

  // Map of pieces that currently choked peers were downloading
  var chokedPieces = Map[Int, ActorRef]()

  // Holds which pieces are done
  var completedPieces = BitSet()

  // Which pieces are currently being requested
  var requestedPieces = BitSet()

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
      val idx = rarest(possibles, RarePieceJitter)
      val size =
        if (idx == numPieces - 1)
          totalSize - (pieceSize * (numPieces - 1))
        else
          pieceSize

      if (idx >= 0) {
        // Tell the choked peer to clear its currently downloading piece
        // if chosen piece coincides
        if (chokedPieces contains idx) {
          chokedPieces(idx) ! PeerM.ClearPiece
        }
        peer ! PeerM.DownloadPiece(idx, size)
      } else {
        peer ! BT.NotInterested
      }

    // Update frequency of pieces
    case PeerM.PieceAvailable(available) =>
      available match {
        case Right(bitfield) => bitfield foreach { i => update(i, 1) }
        case Left(i) => update(i, 1)
      }

    // Upon peer disconnect, update frequency of lost pieces
    case PeerM.Disconnected(peerId, peerHas) =>
      peerHas foreach { i => update(i, -1) }

    // Save pieces index and actorRef for piece download resumption
    // Message is forwarded from TorrentClient, so original peer ActorRef is
    // retained
    case PeerM.ChokedOnPiece(idx) =>
      chokedPieces += (idx -> sender)

    case PeerM.Resume(idx) =>
      chokedPieces -= idx

    // Peer is connected, send a bitfield message
    case msg: PeerM.Connected =>
      sender ! BT.Bitfield(completedPieces, numPieces)

    // Delegate choosing logic to a future
    case PeerM.Ready(peerHas) =>
      val peer = sender
      val pieces = piecesSet.toList
      future {
        val possibles = peerHas &~ (completedPieces | requestedPieces)
        val idx = rarest(possibles, pieces, RarePieceJitter)
        val size =
          if (idx == numPieces - 1)
            totalSize - (pieceSize * (numPieces - 1))
          else
            pieceSize

        if (idx >= 0) {
          // Tell the choked peer to clear its currently downloading piece
          // if chosen piece coincides
          if (chokedPieces contains idx) {
            chokedPieces(idx) ! PeerM.ClearPiece
            chokedPieces -= idx
          }
          peer ! PeerM.DownloadPiece(idx, size)
          requestedPieces += idx
        } else {
          peer ! BT.NotInterested
        }
      }

  }

  def add(piece: PieceInfo): Unit = {
    piecesMap += (piece.index -> piece)
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
  def rarest(possibles: BitSet, pieces: List[PieceInfo], k: Int): Int = {
    val availablePiecesBuffer = mutable.ArrayBuffer[Int]()

    @tailrec
    def populateRarePieces(count: Int): Unit = {
      if (count <= k && !pieces.isEmpty) {
        val pieceInfo = pieces.head
        if (possibles(pieceInfo.index)) {
          availablePiecesBuffer += pieceInfo.index
        }
        populateRarePieces(pieces.tail, count + 1)
      }
    }

    populateRarePieces(0)
    val chosenPieces = availablePiecesBuffer.toArray
    if (chosenPieces.isEmpty) {
      -1
    } else {
      val index = chosenPieces(
        (new Random).nextInt(
          k min chosenPieces.size))
      chosenPieces(index)
    }
  }

}