package org.jerchung.torrent.actor

import akka.actor.{ Actor, ActorRef, Props, Cancellable }
import org.jerchung.torrent.actor.message.BT
import org.jerchung.torrent.Constant
import scala.annotation.tailrec

object BlockRequestor {
  def props(protocol: ActorRef, idx: Int, size: Int): Props = {
    Props(new BlockRequestor(protocol, idx, size) with ProdParent)
  }

  object Message {
    case object Resume
    case class BlockDoneAndRequestNext(offset: Int)
  }

}

/*
 * This actor requests blocks from the peer until the piece is completed.  Sends
 * block requests to the protocol
 */
class BlockRequestor(protocol: ActorRef, idx: Int, size: Int)
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
        protocol ! BT.Request(idx, off, requestSize)
      }
  }

  // Request either up to max requests or until the end of the piece is reached
  // Increment offset as you go and add requested offset to pipeline set
  @tailrec
  private def pipelineRequests(maxRequests: Int, count: Int = 0): Unit = {
    if (count < maxRequests && offset < size) {
      val requestSize = Constant.BlockSize min (size - offset)
      protocol ! BT.Request(idx, offset, requestSize)
      pipeline += offset
      offset += requestSize
      pipelineRequests(maxRequests, count + 1)
    }
  }

}