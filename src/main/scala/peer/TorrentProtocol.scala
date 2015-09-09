package storrent.peer

import storrent.message.{ PeerM, BT }
import akka.actor.{ Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.io.Tcp
import akka.util.ByteString
import java.net.InetSocketAddress
import storrent.Convert._
import storrent.Constant
import storrent.core.Core._
import scala.annotation.tailrec
import scala.collection.BitSet

object TorrentProtocol {
  def props(peer: ActorRef, connection: ActorRef): Props = {
    Props(new TorrentProtocol(peer, connection))
  }
}

/**
 * This actor servers to translate between ByteStrings and TCP Wire Messages.
 * Responses are sent to peer
 */
class TorrentProtocol(peer: ActorRef, connection: ActorRef)
  extends Actor with ActorLogging {

  override def preStart(): Unit = {
    connection ! Tcp.Register(self, keepOpenOnPeerClosed = true)
  }

  override def postRestart(reason: Throwable): Unit = {}

  // TODO - Figure out what to do upon connection close
  def receive: Receive = handle(ByteString.empty)

  def handle(remaining: ByteString): Receive = {
    case msg: BT.Message => handleMessage(msg)
    case Tcp.Received(data) => handleReply(remaining ++ data)
    case m =>
      log.warning(s"TorrentProtocol Received unsupported message $m")
  }

  // Handle each type of tcp peer message that client may want to send
  // Create ByteString based off message type and send to tcp connection
  // TODO - BITFIELD
  def handleMessage(msg: BT.Message) = {
    connection ! Tcp.Write(msg.toBytes)
  }

  // https://wiki.theory.org/BitTorrentSpecification
  // ByteString may come as multiple messages concatenated together, so parse
  // through the given bytestring and send messages to peerclient as they're 'translated'
  // ByteStrings are implicitly converted to Ints / Strings when needed
  @tailrec
  final def handleReply(data: ByteString): Unit = {
    // Need at least 4 bytes for length
    if (data.length >= 4) {
      var length: Int = 0
      var msg: Option[BT.Reply] = None
      if (data.head == 19 && data.slice(1, 20) == BT.protocol) {
        length = 68
        msg = Some(BT.HandshakeR(
          infoHash = data.slice(28, 48),
          peerId = data.slice(48, 68))
        )
      } else {
        length = data.take(4).toInt
        if (length == 0) {
          msg = Some(BT.KeepAliveR)
        } else {
          val idPayload = data.drop(4)
          if (idPayload.length >= length) {
            msg = Some(parseCommonReply(idPayload, length))
          } else {
            context.become(handle(data))
          }
        }
      }

      msg match {
        case Some(m) =>
          peer ! m
          handleReply(data.drop(length + 4))
        case None => ()
      }
    } else {
      context.become(handle(data))
    }
  }

  // Length includes id byte
  def parseCommonReply(data: ByteString, length: Int): BT.Reply = {
    data.headOption match {
      case Some(0) => BT.ChokeR
      case Some(1) => BT.UnchokeR
      case Some(2) => BT.InterestedR
      case Some(3) => BT.NotInterestedR
      case Some(4) => BT.HaveR(data.slice(1, 5).toInt)
      case Some(5) => BT.BitfieldR(data.slice(1, length - 1).toBitSet)
      case Some(6) => BT.RequestR(
        index = data.slice(1, 5).toInt,
        offset = data.slice(5, 9).toInt,
        length = data.slice(9, 13).toInt)
      case Some(7) => BT.PieceR(
        index = data.slice(1, 5).toInt,
        offset = data.slice(5, 9).toInt,
        block = data.slice(9, length))
      case Some(8) => BT.CancelR(
        index = data.slice(1, 5).toInt,
        offset = data.slice(5, 9).toInt,
        length = data.slice(9, 13).toInt)
      case Some(9) => BT.PortR(data.slice(1, 3).toInt)
      case Some(b) => BT.InvalidR(Some(data))
      case None => BT.InvalidR(None)
    }
  }

}
