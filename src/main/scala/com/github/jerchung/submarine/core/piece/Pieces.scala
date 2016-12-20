package com.github.jerchung.submarine.core.piece

import java.security.MessageDigest

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.EventStream
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.{Core, Torrent}
import com.github.jerchung.submarine.core.peer.Peer
import com.github.jerchung.submarine.core.persist.SubmarineFile
import com.github.jerchung.submarine.core.util.FilePathUtil
import com.twitter.util.LruMap

import scala.language.postfixOps

object Pieces {
  def props(args: Args): Props = {
    Props(new Pieces(args) with AppCake)
  }

  case class Args(torrent: Torrent,
                  rootFolder: String,
                  name: String,
                  torrentEvents: EventStream)

  trait Provider extends Core.Cake#Provider {
    def piecesPersist(args: Args): ActorRef
  }

  trait AppProvider extends Provider {
    def piecesPersist(args: Args): ActorRef =
      context.actorOf(Pieces.props(args))
  }

  trait Cake {
    this: Pieces =>
    def provider: Provider

    trait Provider extends SubmarineFile.Provider
  }

  trait AppCake extends Cake { this: Pieces =>
    val provider = new Provider with SubmarineFile.AppProvider
  }

  def hashMatches(hash: IndexedSeq[Byte], piece: Array[Byte]): Boolean = {
    val digest: Array[Byte] = MessageDigest.getInstance("SHA-1").digest(piece)
    hash.sameElements(digest)
  }
}

class Pieces(args: Pieces.Args)
  extends Actor with ActorLogging { this: Pieces.Cake =>

  val CacheSize = 10

  val torrent: Torrent = args.torrent

  // Important values
  val numPieces: Int = torrent.numPieces
  val pieceSize: Int = torrent.pieceSize
  val totalSize: Int = torrent.totalSize

  // Cache map for quick piece access (pieceIndex -> Piece)
  // is LRU since it's not feasible to store ALL pieces in memory
  val cachedPieces = new LruMap[Int, Array[Byte]](CacheSize)

  // Buffered memory (SHARED) for everything.  Max bytes read at any point will be pieceSize
  val sharedBuffer: Array[Byte] = new Array[Byte](pieceSize)

  // Actor that takes care of reading / writing from disk
  val submarineFile: SubmarineFile = {
    // Single file will write to a file of this path, multi file will write to a folder of this path
    val path = FilePathUtil.pathJoin(args.rootFolder, args.name)
    torrent.fileMode match {
      case Torrent.FileMode.Single => provider.singleFile(path, totalSize)
      case Torrent.FileMode.Multiple => provider.multiFile(torrent.files, path, totalSize)
    }
  }

  override def preStart(): Unit = {
    args.torrentEvents.subscribe(self, classOf[Peer.Announce])
  }

  def receive: Receive = handlePieceMessages

  def handlePieceMessages: Receive = {
    case Peer.Announce(peer, publish) =>
      publish match {
        case Peer.RequestPiece(index, offset, length) if isWithin(index) =>
          if (cachedPieces.contains(index)) {
            peer ! Peer.Message.PieceBlock(index, offset, ByteString.fromArray(cachedPieces(index), offset, length))
          } else if (isWithin(index)) {
            val pieceOffset = index * pieceSize
            val chunkLength = pieceSize.min(totalSize - pieceOffset)
            val bytesRead = submarineFile.readBytes(pieceOffset, chunkLength, sharedBuffer)

            val pieceCopy = sharedBuffer.slice(0, bytesRead)

            // TODO(jerry): Verify on the bytesRead, make sure it's the correct amount
            peer ! Peer.Message.PieceBlock(index, offset, ByteString.fromArray(pieceCopy, offset, length))
            cachedPieces.put(index, pieceCopy)
          }

        case Peer.PieceDone(index, piece) if isWithin(index) =>
          val pieceOffset = index * pieceSize

          submarineFile.writeBytes(pieceOffset, piece)
          cachedPieces.put(index, piece)

        case _ => ()
      }
  }

  private def isWithin(index: Int): Boolean = {
    index < numPieces && index >= 0
  }
}
