package org.jerchung.torrent.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.actor.PoisonPill
import akka.util.ByteString
import org.jerchung.torrent.actor.message.{ TorrentM, TrackerM, BT, PeerM }
import scala.annotation.tailrec
import scala.collection.BitSet
import scala.collection.mutable
import scala.collection.SortedSet
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
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

  case class ChosenPiece(index: Int, peer: ActorRef, possibles: BitSet)
  case class ChoosePiece(possibles: BitSet, pieces: SortedSet[PieceInfo])

  object PieceChooser {
    def props(peer: ActorRef): Props = {
      Props(new PieceChooser(peer) with ProdParent)
    }
  }

  class PieceChooser(peer: ActorRef) extends Actor { this: Parent =>

    val RarePieceJitter = 20

    def receive = {
      case ChoosePiece(possibles, pieces) =>
        val idx = rarest(possibles, pieces.toList)
        parent ! ChosenPiece(idx, peer, possibles)
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

    case ChosenPiece(idx, peer, possibles) =>
      val size =
        if (idx == numPieces - 1)
          totalSize - (pieceSize * (numPieces - 1))
        else
          pieceSize

      // If somewhile this piece was already chosen while the piece chooser
      // actor was working, we remove the idx from the possibles and tell it to
      // rechoose
      if (idx >= 0) {
        if (!(completedPieces | requestedPieces).contains(idx)) {
          chokedPieces.get(idx) map { _ ! PeerM.ClearPiece }
          peer ! PeerM.DownloadPiece(idx, size)
          requestedPieces += idx
          sender ! PoisonPill
        } else {
          sender ! ChoosePiece(possibles - idx, pieces)
        }
      } else {
        peer ! BT.NotInterested
        sender ! PoisonPill
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

    // Delegate choosing logic to a PieceChooser actor for async
    case PeerM.Ready(peerHas) =>
      val peer = sender
      val possibles = peerHas &~ (completedPieces | requestedPieces)
      context.actorOf(PieceChooser.props())

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


}