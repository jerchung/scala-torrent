package org.jerchung.torrent.actor

import akka.actor.{ Actor, ActorRef, Props }
import akka.util.ByteString
import scala.util.Random
import scala.collections.mutable

object PeerManager {
  def props: Props = Props(classOf[PeerManager])
}

/*
* Automatically decide which peers to unchoke / choke based on
* upload rates from connected peers.
*
* Also has a block manager to manage read / writes to and from disk
*/
class PeerManager extends Actor {

  val ClientId = "ST"
  val Version = "1000"
  val ID = s"-${ClientId + Version}-${randomIntString(12)}"

  val connectedPeers = mutable.Map[ByteString, ActorRef]()
  val blockManager = context.actorOf(FileManager.props)

  def receive = {
    case CreatePeer(peerId, infoHash, protocol) =>
      val peer = context.actorOf(PeerClient.props)
      connectedPeers(peerId) = peer
      sender ! peer
    case p: Props => sender ! context.actorOf(p)
  }

  def randomIntString(length: Int, random: Random = new Random): String = {
    if (length > 0) {
      random.nextInt(10).toChar + randomIntString(random, length - 1)
    } else {
      ""
    }
  }

  def broadcast(message: Message): Unit = {
    connectedPeers foreach { (id, peer) => peer ! message }
  }


}