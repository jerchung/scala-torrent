package com.github.jerchung.submarine.core.piece

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.event.EventStream
import com.github.jerchung.submarine.core.protocol.TorrentProtocol

import scala.concurrent.duration.FiniteDuration

object PiecePipeline {
  def props(args: Args): Props = {
    Props(new PiecePipeline(args))
  }

  case class Args(pieceIndex: Int,
                  pieceSize: Int,
                  blockSize: Int,
                  pieceHash: IndexedSeq[Byte],
                  maxPerPeer: Int,
                  torrentEvents: EventStream)

  case class Attach(peer: ActorRef)
  case class Detach(peer: ActorRef)

  sealed trait Announce
  case class Done(index: Int, piece: Array[Byte]) extends Announce
  case class Invalid(index: Int) extends Announce
  case class Priority(index: Int, isHigh: Boolean) extends Announce

  private case class RetryMetadata(triedPeers: Set[ActorRef], retryTask: Cancellable)
  private case class Retry(offset: Int, length: Int)
}

class PiecePipeline(args: PiecePipeline.Args) extends Actor {
  val RetryInterval: FiniteDuration = FiniteDuration(30, TimeUnit.SECONDS)

  var isStarted = false
  var nextOffset = 0
  var pieceData: PieceData = PieceData.Incomplete(args.pieceIndex, args.pieceSize, args.pieceHash)

  var retries: Map[(Int, Int), PiecePipeline.RetryMetadata] = Map()

  var peerActives: Map[ActorRef, Int] = Map()

  // Peers from past and present
  var allPeers: Set[ActorRef] = Set()

  def receive: Receive = {
    case PiecePipeline.Attach(peer) =>
      allPeers += peer
      peerActives += (peer -> 0)
      requestNextBlocks(peer, args.maxPerPeer)
      args.torrentEvents.publish(PiecePipeline.Priority(index = args.pieceIndex, isHigh = false))

    case PiecePipeline.Detach(peer) =>
      peerActives -= peer

      if (isStarted && peerActives.isEmpty) {
        args.torrentEvents.publish(PiecePipeline.Priority(index = args.pieceIndex, isHigh = true))
      }

    case TorrentProtocol.Reply.Piece(index, offset, block) if index == args.pieceIndex &&
                                                              peerActives.contains(sender) &&
                                                              retries.contains((offset, block.size)) =>
      isStarted = true

      // Cancel everything related to this current retry (it's done)
      val retry = retries((offset, block.size))
      retry.retryTask.cancel()
      retry.triedPeers.foreach { _ ! TorrentProtocol.Send.Cancel(index, offset, block.size) }
      retries -= ((offset, block.size))

      // Check state of the data
      pieceData = pieceData.update(offset, block)
      pieceData match {
        case PieceData.Complete(_, piece) =>
          // We're DONE
          args.torrentEvents.publish(PiecePipeline.Done(index, piece))
          context.stop(self)
        case _: PieceData.Invalid =>
          // TODO(jerry) - Figure out invalid case (probably something to do with allPeers)
        case _: PieceData.Incomplete =>
          requestNextBlocks(sender, 1)
      }

    // Don't retry on the current peer, retry on a new peer (if available)
    case PiecePipeline.Retry(offset, length) if retries.contains((offset, length)) =>
      val retry = retries((offset, length))
      (availablePeers &~ retry.triedPeers).headOption match {
        case Some(peer) =>
          peer ! TorrentProtocol.Send.Request(args.pieceIndex, offset, length)
          retries += ((offset, length) -> retry.copy(triedPeers = retry.triedPeers + peer))
          peerActives += (peer -> (peerActives(peer) + 1))
        case None => ()
      }
  }

  private def requestNextBlocks(peer: ActorRef, numBlocks: Int): Unit = {
    var n = 0
    while (n < numBlocks && nextOffset < args.pieceSize) {
      val blockSize = args.blockSize.min(args.pieceSize - nextOffset)
      requestAndScheduleRetry(peer, nextOffset, blockSize, RetryInterval)
      nextOffset += blockSize
      n += 1
    }

    peerActives += (peer -> (peerActives(peer) + n))
  }

  private def requestAndScheduleRetry(peer: ActorRef, offset: Int, length: Int, retryInterval: FiniteDuration): Unit = {
    peer ! TorrentProtocol.Send.Request(args.pieceIndex, offset, length)
    val retryTask = context.system.scheduler.schedule(
      retryInterval,
      retryInterval,
      self,
      PiecePipeline.Retry(offset, length)
    )

    retries += ((offset, length) -> PiecePipeline.RetryMetadata(Set(peer), retryTask))
  }

  private def availablePeers: Set[ActorRef] =
    peerActives
      .collect { case (peer, count) if count < args.maxPerPeer => peer }
      .toSet
}
