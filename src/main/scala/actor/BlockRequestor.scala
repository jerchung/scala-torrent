package org.jerchung.torrent.actor

import akka.actor.{ Actor, ActorRef, Props, Cancellable }
import org.jerchung.torrent.actor.message.BT
import org.jerchung.torrent.Constant
import scala.annotation.tailrec

object BlockRequestor {
  def props(protocol: ActorRef, index: Int, size: Int): Props = {
    Props(new BlockRequestor(protocol, index, size) with ProdParent)
  }

  object Message {
    case object Resume
    case class BlockDoneAndRequestNext(offset: Int)
  }

}

/*
 * This actor requests blocks from the peer until the piece is completed.  Sends
 * block requests to the protocol.  Index is index of the piece that the
 * requestor is requesting blocks for
 */
class BlockRequestor(protocol: ActorRef, index: Int, size: Int)
    extends Actor { this: Parent =>

  import BlockRequestor.Message._

  val MaxRequestPipeline = 5
  var offset = 0
  var pipeline = Set[Int]()

  // Want to fill up pipeline with requests at initialization
  override def preStart(): Unit = {
    pipelineRequests(MaxRequestPipeline)
  }

  def receive = {

    // Either requests the next block or does nothing depending on where the
    // offset is incremented to
    case BlockDoneAndRequestNext(off) =>
      pipeline -= off
      pipelineRequests(1)

    // Re-request any straggling blocks which were not actually requested due
    // to peer choking
    case Resume =>
      pipeline foreach { off =>
        val requestSize = Constant.BlockSize min (size - off)
        protocol ! BT.Request(index, off, requestSize)
      }
  }

  // Request either up to max requests or until the end of the piece is reached
  // Increment offset as you go and add requested offset to pipeline set
  private def pipelineRequests(maxRequests: Int): Unit = {

    @tailrec
    def pipelineHelper(
        count: Int,
        requests: List[BT.Request],
        pipeline: Set[Int],
        offset: Int):
        (List[BT.Request], Set[Int], Int) = {
      if (count >= maxRequests || offset >= size) {
        (requests, pipeline, offset)
      } else {
        val requestSize = Constant.BlockSize min (size - offset)
        val request = BT.Request(index, offset, requestSize)
        pipelineHelper(
          count + 1,
          requests :: request,
          pipeline + offset,
          offset + requestSize
        )
      }
    }

    val (requests, morePipeline, newOffset) =
      pipelineHelper(0, List[BT.Request](), Set[Int](), offset)

    pipeline += morePipeline
    offset = newOffset
    requests foreach { protocol ! _ }

  }

}