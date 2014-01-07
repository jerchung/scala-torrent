package org.jerchung.torrent

import AM.{ PeerM, BT }
import akka.actor.{ Actor, ActorRef, Props }
import akka.util.ByteString
import java.net.InetSocketAddress

object PeerClient {
  def props(peer: Peer, infoHash: ByteString) =
    Props(classOf[PeerClient], peer, infoHash)
}

// One of these actors per peer
class PeerClient(peer: Peer, infoHash: ByteString) extends Actor {

  import context.parent

  val remote = new InetSocketAddress(peer.ip, peer.port)
  val protocol: ActorRef = context.actorOf(TorrentProtocol.props(remote))

  def receive = {
    // Messages from protocol
    case BT.Connected =>
      parent ! PeerM.Connected
      protocol ! BT.Handshake(infoHash, peer.id)
    case BT.Reply.Handshake(infoHash, peerId) =>
      if (peerId != peer.id) {
        parent ! "Failed peerId matching"
        context stop self
      }
    case
  }

}