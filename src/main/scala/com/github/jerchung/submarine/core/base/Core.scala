package com.github.jerchung.submarine.core.base

import akka.actor.{Actor, ActorRef, Scheduler}
import akka.event.EventStream
import akka.http.scaladsl.{Http, HttpExt}
import akka.io.{IO, Tcp}

/**
 * Allows for testability so that messages that actors send to their parents
 * can be intercepted while still allowing for a direct reference to the actor
 * for full control
 */

object Core {
  trait Cake { this: Actor =>
    lazy val _c = context

    trait Provider {
      lazy val context = _c
    }
  }

  trait SchedulerProvider extends Core.Cake#Provider {
    def scheduler: Scheduler
  }

  trait AppSchedulerProvider extends SchedulerProvider {
    override def scheduler: Scheduler = context.system.scheduler
  }

  trait Parent { this: Actor =>
    def parent: ActorRef
  }
  trait AppParent extends Parent { this: Actor =>
    val parent = context.parent
  }

  trait TcpService { this: Actor =>
    def tcp: ActorRef
  }

  trait AppTcpService extends TcpService { this: Actor =>
    import context.system
    val tcp = IO(Tcp)
  }

  trait HttpService { this: Actor =>
    def http: HttpExt
  }

  trait AppHttpService extends HttpService { this: Actor =>
    val http = Http(context.system)
  }

  trait MessageBus {
    def torrentEvents: EventStream
  }
}
