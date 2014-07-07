package org.jerchung.torrent.actor

import akka.actor.{ Actor, ActorRef, Props, Cancellable }
import com.escalatesoft.subcut.inject._
import org.jerchung.torrent.actor.message.BT
import org.jerchung.torrent.Constant
import org.jerchung.torrent.dependency.BindingKeys._
import scala.annotation.tailrec

object BlockRequestor {
  def props(index: Int, size: Int): Props = {
    Props(new BlockRequestor(index, size) with ProdParent)
  }

  case object Resume
  case class BlockDoneAndRequestNext(offset: Int)

}

/*
 * This actor requests blocks from the peer until the piece is completed.  Sends
 * block requests to the protocol.  Index is index of the piece that the
 * requestor is requesting blocks for
 */
class BlockRequestor(index: Int, size: Int) extends Actor with AutoInjectable {

  import BlockRequestor._

  val MaxRequestPipeline = 5
  var offset = 0
  var pipeline = Set[Int]()

  val parent = injectOptional [ActorRef](ParentId) getOrElse { context.parent }

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

  }

  // Request either up to max requests or until the end of the piece is reached
  // Increment offset as you go and add requested offset to pipeline set
  private def pipelineRequests(maxRequests: Int): Unit = {

    @tailrec
    def pipelineHelper(
        count: Int,
        pipeline: Set[Int],
        offset: Int):
        (Set[Int], Int) = {
      if (count >= maxRequests || offset >= size) {
        (pipeline, offset)
      } else {
        val requestSize = Constant.BlockSize min (size - offset)
        val request = BT.Request(index, offset, requestSize)
        parent ! request
        pipelineHelper(
          count + 1,
          pipeline + offset,
          offset + requestSize
        )
      }
    }

    val (morePipeline, newOffset) = pipelineHelper(0, Set[Int](), offset)
    pipeline ++= morePipeline
    offset = newOffset

  }

}