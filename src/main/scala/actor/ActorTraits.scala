package org.jerchung.torrent.actor

import akka.actor.Actor
import akka.actor.ActorRef
import akka.io.IO
import akka.io.Tcp

/**
 * Allows for testability so that messages that actors send to their parents
 * can be intercepted whil still allowing for a direct reference to the actor
 * for full control
 */
trait Parent {
  def parent: ActorRef
}

trait ProdParent extends Parent { this: Actor =>
  val parent = context.parent
}

trait TestParent extends Parent { this: Actor =>
  val parent: ActorRef
}

trait TcpManager {
  def tcpManager: ActorRef
}

trait ProdTcpManager extends TcpManager { this: Actor =>
  import context.system
  val tcpManager = IO(Tcp)
}

trait TestTcpManager extends TcpManager { this: Actor =>
  val tcpManager: ActorRef
}