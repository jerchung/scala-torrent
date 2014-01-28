package org.jerchung.torrent.actor

import akka.actor.actor
import akka.actor.ActorRef
import akka.actor.Props
import scala.collection.mutable

object PeerRouter {
  def props: Props = {
    Props(new PeerRouter with ProdParent)
  }
}

class PeerRouter extends Actor { this: Parent =>

  val connectedPeers = mutable.Map[ByteString, ActorRef]()

  val openPeers = mutable.Map[ByteString, ActorRef]()


}