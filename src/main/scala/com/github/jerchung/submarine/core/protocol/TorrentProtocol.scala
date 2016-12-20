package com.github.jerchung.submarine.core.protocol

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.Tcp
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.Core
import com.github.jerchung.submarine.core.behavior.PubSub
import com.github.jerchung.submarine.core.implicits.Convert._
import com.github.jerchung.submarine.core.setting.Constant

import scala.annotation.tailrec
import scala.collection.BitSet

object TorrentProtocol {
  def props(args: Args): Props = {
    Props(new TorrentProtocol(args))
  }

  case class Args(peer: ActorRef,
                  connection: ActorRef)

  trait Provider extends Core.Cake#Provider {
    def torrentProtocol(args: Args): ActorRef
  }

  trait AppProvider extends Provider {
    override def torrentProtocol(args: Args): ActorRef =
      context.actorOf(TorrentProtocol.props(args))
  }

  final val PROTOCOL = ByteString.fromString("BitTorrent protocol")

  /**
    * Take in the intended length of the ByteString and the numbers to concatenate together,
    * construct a ByteString with the byte form of the numbers in order that they were passed in with the appropriate
    * number of leading 0s to fit the ByteString to the length argument
 *
    * @param length
    * @param nums
    * @return ByteString appropriately formatted
    */
  private def byteStringify(length: Int, nums: Int*): ByteString = {

    @tailrec
    def byteHelper1(nums: Seq[Int], bytes: ByteString): ByteString = {
      if (nums.isEmpty) {
        bytes
      } else {
        val n = nums.head
        val chunk = byteHelper2(n, 0, ByteString())
        byteHelper1(nums.tail, bytes ++ chunk)
      }
    }

    @tailrec
    def byteHelper2(n: Int, idx: Int, chunk: ByteString): ByteString = {
      if (idx < length) {
        val shift = Constant.ByteSize * (length - 1 - idx)
        byteHelper2(n, idx + 1, chunk :+ ((n >> shift) & 0xFF).toByte)
      } else {
        chunk
      }
    }

    byteHelper1(nums, ByteString())
  }

  // Outgoing messages
  sealed trait Send {
    def toBytes: ByteString
  }

  object Send {
    case object KeepAlive extends Send {
      lazy val toBytes = ByteString(0, 0, 0, 0)
    }

    case object Choke extends Send {
      lazy val toBytes = ByteString(0, 0, 0, 1, 0)
    }

    case object Unchoke extends Send {
      lazy val toBytes = ByteString(0, 0, 0, 1, 1)
    }

    case object Interested extends Send {
      lazy val toBytes = ByteString(0, 0, 0, 1, 2)
    }

    case object NotInterested extends Send {
      lazy val toBytes = ByteString(0, 0, 0, 1, 3)
    }

    case class Bitfield(bitfield: BitSet, numPieces: Int) extends Send {
      lazy val toBytes = {
        val numBytes = math.ceil(numPieces.toFloat / Constant.ByteSize).toInt
        byteStringify(4, 1 + numBytes) ++ ByteString(5) ++
          bitfield.toByteString(numBytes)
      }
    }

    case class Have(index: Int) extends Send {
      lazy val toBytes = ByteString(0, 0, 0, 5, 4) ++ byteStringify(4, index)
    }

    case class Request(index: Int, offset: Int, length: Int) extends Send {
      lazy val toBytes =
        ByteString(0, 0, 0, 13, 6) ++ byteStringify(4, index, offset, length)
    }

    case class Piece(index: Int, offset: Int, block: ByteString) extends Send {
      lazy val toBytes =
        byteStringify(4, 9 + block.length) ++ ByteString(7) ++
          byteStringify(4, index, offset) ++ block
    }

    case class Cancel(index: Int, offset: Int, length: Int) extends Send {
      lazy val toBytes =
        ByteString(0, 0, 0, 13, 8) ++ byteStringify(4, index, offset, length)
    }

    case class Port(port: Int) extends Send {
      lazy val toBytes = ByteString(0, 0, 0, 3, 9) ++ byteStringify(2, port)
    }

    case class Handshake(infoHash: ByteString, peerId: ByteString) extends Send {
      lazy val toBytes = {
        val reserved = ByteString(0, 0, 0, 0, 0, 0, 0, 0)
        ByteString(19) ++ TorrentProtocol.PROTOCOL ++ reserved ++ infoHash ++ peerId
      }
    }
  }

  // Incoming messages
  sealed trait Reply {
    def isValid: Boolean = true
  }

  object Reply {
    case object KeepAlive extends Reply
    case object Choke extends Reply
    case object Unchoke extends Reply
    case object Interested extends Reply
    case object NotInterested extends Reply
    case class Bitfield(bitfield: BitSet) extends Reply
    case class Have(index: Int) extends Reply
    case class Request(index: Int, offset: Int, length: Int) extends Reply
    case class Piece(index: Int, offset: Int, block: ByteString) extends Reply
    case class Cancel(index: Int, offset: Int, length: Int) extends Reply
    case class Port(port: Int) extends Reply
    case class Handshake(infoHash: ByteString, peerId: ByteString) extends Reply
    case object Connected extends Reply
    case class Invalid(data: Option[ByteString]) extends Reply // Invalid ByteString from com.github.jerchung.submarine.core.peer
  }

  case object Ack extends Tcp.Event

  case class Subscribe(classifier: Class[Reply])
  case class Unsubscribe(classifier: Class[Reply])
}

/**
 * This actor servers to translate between ByteStrings and TCP Wire Messages.
 * Responses are sent to peer
 */
class TorrentProtocol(args: TorrentProtocol.Args) extends Actor with ActorLogging {

  import TorrentProtocol.{Reply, Send}

  val peer = args.peer
  val connection = args.connection

  override def preStart(): Unit = {
    connection ! Tcp.Register(self, keepOpenOnPeerClosed = true)
  }

  override def postRestart(reason: Throwable): Unit = {}

  // TODO - Figure out what to do upon connection close
  def receive: Receive = handle(ByteString.empty)

  // TODO - Figure out Handshake / bitfield ACK
  def handle(remaining: ByteString): Receive = {
    case msg: Send => handleMessage(msg)
    case Tcp.Received(data) => handleReply(remaining ++ data)

    case m =>
      log.warning(s"TorrentProtocol Received unsupported message $m")
  }

  // Handle each type of tcp peer message that client may want to send
  // Create ByteString based off message type and send to tcp connection
  def handleMessage(message: Send): Unit = {
    connection ! Tcp.Write(message.toBytes)
  }

  // https://wiki.theory.org/BitTorrentSpecification
  // ByteString may come as multiple messages concatenated together, so parse
  // through the given bytestring and send messages to peerclient as they're 'translated'
  // ByteStrings are implicitly converted to Ints / Strings when needed
  def handleReply(data: ByteString): Unit = {
    val (reply, remaining) = parseReply(data)
    reply.foreach { r => peer ! r }
    context.become(handle(remaining))
  } 

  // ByteString of at least len 4 gets passed into here
  private def parseReply(data: ByteString): (Option[TorrentProtocol.Reply], ByteString) = {
    var length = 0
    var reply: Option[Reply] = None
    if (data.size > 4) {
      if (data.head == 19 && data.slice(1, 20) == TorrentProtocol.PROTOCOL) {
        length = 68
        if (data.size >= 68) {
          reply = Some(Reply.Handshake(
            infoHash = data.slice(28, 48),
            peerId = data.slice(48, 68))
          )
        }
      } else {
        val (payloadLenBytes, payload) = data.splitAt(4)
        val payloadLength = payloadLenBytes.toInt
        length = payloadLength + 4
        reply = if (payloadLength == 0)
          Some(Reply.KeepAlive)
        else if (payload.size >= payloadLength)
          Some(parseCommonReply(payload, payloadLength))
        else
          None
      }
    }

    val finalData = if (data.size < length) data else data.drop(length)

    (reply, finalData)
  }

  // Length includes id byte
  private def parseCommonReply(data: ByteString, length: Int): Reply = {
    data.headOption match {
      case Some(0) => Reply.Choke
      case Some(1) => Reply.Unchoke
      case Some(2) => Reply.Interested
      case Some(3) => Reply.NotInterested
      case Some(4) => Reply.Have(data.slice(1, 5).toInt)
      case Some(5) => Reply.Bitfield(data.slice(1, length - 1).toBitSet)
      case Some(6) => Reply.Request(
        index = data.slice(1, 5).toInt,
        offset = data.slice(5, 9).toInt,
        length = data.slice(9, 13).toInt)
      case Some(7) => Reply.Piece(
        index = data.slice(1, 5).toInt,
        offset = data.slice(5, 9).toInt,
        block = data.slice(9, length))
      case Some(8) => Reply.Cancel(
        index = data.slice(1, 5).toInt,
        offset = data.slice(5, 9).toInt,
        length = data.slice(9, 13).toInt)
      case Some(9) => Reply.Port(data.slice(1, 3).toInt)
      case Some(b) => Reply.Invalid(Some(data))
      case None => Reply.Invalid(None)
    }
  }

}
