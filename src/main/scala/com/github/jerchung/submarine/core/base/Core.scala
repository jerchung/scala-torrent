package com.github.jerchung.submarine.core.base

import akka.actor.{Actor, ActorContext, ActorRef, Scheduler}
import akka.event.EventStream
import akka.http.scaladsl.{Http, HttpExt}
import akka.io.{IO, Tcp}

/**
 * Allows for testability so that messages that actors send to their parents
 * can be intercepted while still allowing for a direct reference to the actor
 * for full control
 */

object Core {

  class AppProvider(val context: ActorContext)

  trait SchedulerService { this: Actor =>
    def scheduler: Scheduler
  }

  trait AppSchedulerService extends SchedulerService { this: Actor =>
    val scheduler = context.system.scheduler
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
}
