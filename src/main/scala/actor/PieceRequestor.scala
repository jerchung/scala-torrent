package org.jerchung.torrent.actor

import akka.actor.{ Actor, ActorRef, Props, Cancellable }
import org.jerchung.torrent.message.BT
import org.jerchung.torrent.Constant
import scala.annotation.tailrec

object PieceRequestor {
  def props(protocol: ActorRef, idx: Int, size: Int): Props = {
    Props(new PieceDownloader(protocol) with ProdParent)
  }

  sealed trait Request
  case object NextBlock extends Request
}

/*
 * This actor requests blocks from the peer until the piece is completed
 */
class PieceRequestor(protocol: ActorRef, idx: Int, size: Int)
  extends Actor { this: Parent =>

  val MaxRequestPipeline = 5
  var offset = 0

  // Want to fill up pipeline with requests
  override def preStart(): Unit = {
    pipelineRequests(MaxRequestPipeline)
  }

  def receive = {

    // Either requests the next block or does nothing depending on where the
    // offset is incremented to
    case NextBlock =>
      pipelineRequests(1)
  }

  // Request either up to max requests or until the end of the piece is reached
  // Increment offset as you go and add requested offset to pipeline set
  @tailrec
  def pipelineRequests(maxRequests: Int, count: Int = 0): Unit = {
    if (count < maxRequests && offset < size) {
      val requestSize = Constant.BlockSize min (size - offset)
      protocol ! BT.Request(idx, offset, requestSize)
      offset += requestSize
      pipelineRequests(maxRequests, count + 1)
    }
  }

}