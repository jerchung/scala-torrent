package org.jerchung.torrent.actor

import org.jerchung.torrent.actor.message.{ PeerM, BT }
import akka.actor.{ Actor, ActorRef, Props, PoisonPill }
import akka.io.Tcp
import akka.util.ByteString
import java.net.InetSocketAddress
import org.jerchung.torrent.Convert._
import org.jerchung.torrent.Constant
import scala.annotation.tailrec
import scala.collection.BitSet

object TorrentProtocol {
  def props(connection: ActorRef): Props = {
    Props(new TorrentProtocol(connection) with ProdParent)
  }
}

/**
 * This actor servers to translate between ByteStrings and TCP Wire Messages.
 * The parent of this actor should always be a Peer Actor, thus responses are
 * send to parent
 */
class TorrentProtocol(connection: ActorRef) extends Actor { this: Parent =>

  override def preStart(): Unit = {
    connection ! Tcp.Register(self)
  }

  override def postRestart(reason: Throwable): Unit = {}

  // TODO - Figure out what to do upon connection close
  def receive: Receive = {
    case msg: BT.Message => handleMessage(msg)
    case Tcp.Received(data) => handleReply(data)
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
  def handleReply(data: ByteString): Unit = {
    if (!data.isEmpty) {
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
          msg = Some(parseCommonReply(data.drop(4), length))
        }
      }

      msg map { m => parent ! m }
      handleReply(data.drop(length))
    }
  }

  def parseCommonReply(data: ByteString, length: Int): BT.Reply = {
    data.headOption match {
      case Some(0) => BT.ChokeR
      case Some(1) => BT.UnchokeR
      case Some(2) => BT.InterestedR
      case Some(3) => BT.NotInterestedR
      case Some(4) => BT.HaveR(data.slice(5, 9).toInt)
      case Some(5) => BT.BitfieldR(data.slice(5, length).toBitSet)
      case Some(6) => BT.RequestR(
        index = data.slice(5, 9).toInt,
        offset = data.slice(9, 13).toInt,
        length = data.slice(13, 17).toInt)
      case Some(7) => BT.PieceR(
        index = data.slice(5, 9).toInt,
        offset = data.slice(9, 13).toInt,
        block = data.slice(13, length))
      case Some(8) => BT.CancelR(
        index = data.slice(5, 9).toInt,
        offset = data.slice(9, 13).toInt,
        length = data.slice(13, 17).toInt)
      case Some(9) => BT.PortR(data.slice(5, 7).toInt)
      case _       => BT.InvalidR
    }
  }

}