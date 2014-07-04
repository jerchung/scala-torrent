package org.jerchung.torrent.actor

import akka.actor.PoisonPill
import akka.actor.{Actor, ActorRef, Props}
import akka.util.ByteString
import com.escalatesoft.subcut.inject._
import org.jerchung.torrent.actor.message.{ TorrentM, TrackerM, BT, PeerM }
import org.jerchung.torrent.dependency.BindingKeys._
import scala.annotation.tailrec
import scala.collection.BitSet
import scala.collection.mutable
import scala.collection.SortedSet
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

object PiecesManager {

  def props(numPieces: Int, pieceSize: Int, totalSize: Int): Props = {
    Props(new PiecesManager(numPieces, pieceSize, totalSize) with ProdParent)
  }

  case class PieceInfo(index: Int, var count: Int) extends Ordered[PieceInfo] {
    def compare(that: PieceInfo): Int = {
      count.compare(that.count)
    }

    override def hashCode(): Int = {
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

  /*
   * Actor in charge of choosing a random piece that's rare for a specific
   * peer that has sent a PeerM.ReadyForPiece message to PiecesManager.  Sends a
   * ChosenPiece message to the PiecesManager upon completion, then gets ended
   */
  class PieceChooser(peer: ActorRef) extends Actor { this: Parent =>

    val RarePieceJitter = 20

    def receive = {
      case ChoosePiece(possibles, pieces) =>
        val idx = rarest(possibles, pieces.toList, RarePieceJitter)
        parent ! ChosenPiece(idx, peer, possibles)
    }

    /*
     * Perform the jittering of choosing from k of the rarest pieces whose
     * indexes are contained in possibles
     * Return the index of the chosen piece
     * Return -1 if no possibilities to choose
     */
    def rarest(possibles: BitSet, pieces: List[PieceInfo], k: Int): Int = {

      @tailrec
      def getRarePieces(
          chosen: Vector[Int](),
          pieces:List[PieceInfo],
          count: Int): Vector[Int] = {
        if (count >= k || pieces.isEmpty) {
          chosen
        } else {
          val pieceInfo = pieces.head
          val newChosen =
            if (possibles contains pieceInfo.index)
              chosen :+ pieceInfo.index
            else
              chosen
          getRarePieces(newChosen, pieces.tail, count + 1)
        }
      }

      val rareIndexes = getRarePieces(Vector[Int](), pieces, 0)
      if (rareIndexes.isEmpty) {
        -1
      } else {
        val randomized = Random.nextInt(k min rareIndexes.size)
        rareIndexes(randomized)
      }
    }

  }

}

/*
 * PiecesManager keeps track of the frequence of each piece.
 * This actor also is in charge of choosing the next piece for a peer to
 * download.
 * This actor also keeps track of currently choked peers.  If a peer was choked
 * in the middle of a download and the piece is left unfinished, if that peer
 * is unchoked before that piece is chosen for download by another peer, then it
 * can resume download of the piece.  Otherwise, the piece download on that peer
 * will be resetted.
 */
class PiecesManager(numPieces: Int, pieceSize: Int, totalSize: Int)
    extends Actor { this: Parent =>

  import PiecesManager._

  // Set of pieces ordered by frequency starting from rarest
  var piecesSet = SortedSet[PieceInfo]()

  // Map of piece index -> pieceInfo to provide access to pieces
  var piecesMap = Map[Int, PieceInfo]()

  // Map of pieces that currently choked peers were downloading (index -> ref)
  var chokedPeers = Map[Int, ActorRef]()

  // Holds which pieces are done
  var completedPieces = BitSet()

  // Which pieces are currently being requested
  var requestedPieces = BitSet()

  // Initialize with all pieces at 0 frequency
  override def preStart(): Unit = {
    (0 until numPieces) foreach { idx =>
      val piece = PieceInfo(idx, 0)
      add(piece)
    }
  }

  def receive = {

    // Message sent from pieceChooser with valid index
    case ChosenPiece(idx, peer, possibles) if (idx >= 0) =>

      // If somehow this piece was already chosen while the piece chooser
      // actor was working, we remove the idx from the possibles and tell it to
      // rechoose.  Otherwise send chosen piece to peer and if a currently choked
      // peer had been downloading that piece tell it to clear that piece and
      // end the choosing actor
      if (completedPieces.contains(idx) || requestedPieces.contains(idx)) {
        val newPossibles = (possibles - idx) &~ (completedPieces | requestedPieces)
        sender ! ChoosePiece(newPossibles, piecesSet)
      } else {
        val size = pieceSize min (totalSize - (pieceSize * idx))
        chokedPeers.get(idx) map { _ ! PeerM.ClearPiece }
        chokedPeers -= idx
        peer ! PeerM.DownloadPiece(idx, size)
        requestedPieces += idx
        sender ! PoisonPill
      }

    // Sent from PieceChooser with invalid index
    case ChosenPiece(idx, peer, possibles) if (idx < 0) =>
      peer ! BT.NotInterested
      sender ! PoisonPill

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
      chokedPeers += (idx -> sender)
      requestedPieces -= idx

    case PeerM.Resume(idx) =>
      chokedPeers -= idx
      requestedPieces += idx

    // Peer is connected, send a bitfield message
    case msg: PeerM.Connected =>
      sender ! BT.Bitfield(completedPieces, numPieces)

    // Delegate piece choosing logic to a PieceChooser actor for async
    case PeerM.ReadyForPiece(peerHas) =>
      val peer = sender
      val possibles = peerHas &~ (completedPieces | requestedPieces)
      val chooser = injectOptional [ActorRef](PieceChooserId) getOrElse {
        context.actorOf(PieceChooser.props(peer))
      }
      chooser ! ChoosePiece(possibles, piecesSet)

    case PeerM.PieceInvalid(idx) =>
      requestedPieces -= idx
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