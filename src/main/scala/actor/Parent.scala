package org.jerchung.torrent.actor

import akka.actor.Actor
import akka.actor.ActorRef

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