package storrent.core

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Scheduler
import akka.io.IO
import akka.io.Tcp

// Hold config information for all actors
case class Config(
  torrentFile: String,
  folderPath: String,
  port: Int
)

/**
 * Allows for testability so that messages that actors send to their parents
 * can be intercepted whil still allowing for a direct reference to the actor
 * for full control
 */

object Core {
  trait Parent { this: Actor =>
    def parent: ActorRef
  }
  trait AppParent extends Parent { this: Actor =>
    val parent = context.parent
  }
  trait TcpManager { this: Actor =>
    def tcpManager: ActorRef
  }
  trait AppTcpManager extends TcpManager { this: Actor =>
    import context.system
    val tcpManager = IO(Tcp)
  }
}
