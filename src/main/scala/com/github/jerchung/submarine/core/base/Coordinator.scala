package com.github.jerchung.submarine.core.base

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.event.EventStream
import akka.util.Timeout
import com.github.jerchung.submarine.core.setting.Constant
import com.github.jerchung.submarine.core.state.TorrentState
import com.github.jerchung.submarine.core.tracker.Tracker

import scala.concurrent.duration._

object Coordinator {
  case class Args(torrent: Torrent,
                  port: Int,
                  tracker: ActorRef,
                  pieces: ActorRef,
                  dispatch: ActorRef,
                  torrentState: ActorRef,
                  torrentEvents: EventStream)

  def props(args: Args): Props = {
    Props(new Coordinator(args))
  }

  case object Initiate

}

/**
  * Deal with re-initializing the correct state if the torrent is resuming and also schedule tracker requests
  * appropriately.
  */
class Coordinator(args: Coordinator.Args) extends Actor {
  import context.dispatcher

  implicit val timeout: Timeout = Timeout(5.seconds)

  override def preStart(): Unit = {
    args.torrentEvents.subscribe(self, classOf[Tracker.Response])
  }

  def receive: Receive = {
    case Coordinator.Initiate =>
      // TODO(jerry): Implement resume logic

      scheduleTrackerAnnounceRequest(0.seconds)

    case announce: Tracker.Response.Announce =>
      scheduleTrackerAnnounceRequest(announce.interval)
  }

  private def scheduleTrackerAnnounceRequest(delay: FiniteDuration): Unit = {
    context.system.scheduler.scheduleOnce(delay) {
      (args.torrentState ? TorrentState.Request.CurrentState)
        .mapTo[TorrentState.Response.CurrentState]
        .foreach { state =>
          args.tracker ! Tracker.Request.Announce(
            args.torrent.announce,
            args.torrent.infoHash,
            Constant.ClientID,
            args.port,
            state.aggregated.totalUploaded,
            state.aggregated.totalDownloaded,
            state.aggregated.totalSize - state.aggregated.totalDownloaded,
            compact = true,
            "start",
            50,
            None
          )
        }
    }
  }
}
