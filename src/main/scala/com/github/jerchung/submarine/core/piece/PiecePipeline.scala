package com.github.jerchung.submarine.core.piece

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import com.github.jerchung.submarine.core.protocol.TorrentProtocol

import scala.concurrent.duration.FiniteDuration

private[peer] object PiecePipeline {
  def props(args: Args): Props = {
    Props(new PiecePipeline(args))
  }

  case class Args(pieceIndex: Int,
                  pieceSize: Int,
                  blockSize: Int,
                  maxConcurrent: Int,
                  pieceHash: IndexedSeq[Byte],
                  peer: ActorRef,
                  protocol: ActorRef)

  private case class BlockRequest(offset: Int, length: Int)
}

private[peer] class PiecePipeline(args: PiecePipeline.Args) extends Actor {
  val MaxRetries = 2
  val RetryDelay: FiniteDuration = FiniteDuration(30, TimeUnit.SECONDS)
  val pieceIndex: Int = args.pieceIndex

  var requestedBlocksRetries: Map[(Int, Int), (Cancellable, Int)] = Map()
  var nextOffset = 0
  var pieceData: PieceData = PieceData.Incomplete(pieceIndex, args.pieceSize, args.pieceHash)

  override def preStart(): Unit = {
    requestNextBlocks()
  }

  override def postStop(): Unit = {
    requestedBlocksRetries.foreach { case (_, retry) => retry._1.cancel() }
  }

  def receive: Receive = {
    case TorrentProtocol.Reply.Piece(index, offset, block) if index == pieceIndex &&
                                                              requestedBlocksRetries.contains((offset, block.size)) =>
      pieceData = pieceData.update(offset, block)
      pieceData match {
        case message @ (_: PieceData.Complete | _: PieceData.Invalid)=>
          args.peer ! message
          context.stop(self)
        case _: PieceData.Incomplete =>
          requestNextBlocks((offset, block.size))
      }

    case PiecePipeline.BlockRequest(offset, length) if requestedBlocksRetries.contains((offset, length)) =>
      val (prevRetry, retryCount) = requestedBlocksRetries((offset, length))
      prevRetry.cancel()
      requestAndScheduleBlock(offset, length, RetryDelay, retryCount + 1)

  }

  private def requestNextBlocks(completedBlocks: (Int, Int)*): Unit = {
    completedBlocks.foreach { completed =>
      if (requestedBlocksRetries.contains(completed)) {
        requestedBlocksRetries(completed)._1.cancel()
      }

      requestedBlocksRetries -= completed
    }

    while (nextOffset < args.pieceSize && requestedBlocksRetries.size < args.maxConcurrent) {
      val currentBlockSize = args.blockSize.min(args.pieceSize - nextOffset)
      requestAndScheduleBlock(nextOffset, currentBlockSize, RetryDelay, 0)

      nextOffset += currentBlockSize
    }
  }

  private def requestAndScheduleBlock(offset: Int, length: Int, delay: FiniteDuration, retryCount: Int): Unit = {
    if (retryCount < MaxRetries) {
      args.protocol ! TorrentProtocol.Send.Request(args.pieceIndex, offset, length)

      val scheduled: Cancellable = context.system.scheduler.scheduleOnce(delay * (retryCount + 1)) {
        self ! PiecePipeline.BlockRequest(offset, length)
      }

      requestedBlocksRetries((offset, length)) = (scheduled, retryCount)
    }
  }
}
