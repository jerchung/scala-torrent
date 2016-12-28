package com.github.jerchung.submarine.core.base

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.event.EventStream
import akka.util.Timeout
import com.github.jerchung.submarine.core.setting.Constant
import com.github.jerchung.submarine.core.state.TorrentState
import com.github.jerchung.submarine.core.tracker.Tracker

import scala.concurrent.duration._

object TorrentCoordinator {
  case class Args(torrent: Torrent,
                  port: Int,
                  tracker: ActorRef,
                  pieces: ActorRef,
                  dispatch: ActorRef,
                  torrentState: ActorRef,
                  torrentEvents: EventStream)

  def props(args: Args): Props = {
    Props(new TorrentCoordinator(args))
  }

  trait Provider {
    def torrentCoordinator(args: Args): ActorRef
  }

  trait AppProvider extends Provider { this: Core.AppProvider =>
    override def torrentCoordinator(args: Args): ActorRef =
      context.actorOf(TorrentCoordinator.props(args))
  }

  case object Initiate

  case class SetState(state: TorrentState.Response.State)

}

/**
  * Deal with re-initializing the correct state if the torrent is resuming and also schedule tracker requests
  * appropriately.
  */
class TorrentCoordinator(args: TorrentCoordinator.Args) extends Actor {
  import context.dispatcher

  implicit val timeout: Timeout = Timeout(5.seconds)

  var lastState: Option[TorrentState.Response.State] = None

  override def preStart(): Unit = {
    args.torrentEvents.subscribe(self, classOf[Tracker.Response])
  }

  override def postStop(): Unit = {
    lastState.foreach(announce(_, "stopped", 0, None))
  }

  def receive: Receive = {
    case TorrentCoordinator.Initiate =>
      // TODO(jerry): Implement resume logic

      scheduleTrackerAnnounceRequest(0.seconds)

    case announce: Tracker.Response.Announce =>
      scheduleTrackerAnnounceRequest(announce.interval)

    case state: TorrentState.Response.State =>
      lastState = Some(state)
      announce(state, "started", 50, None)
  }

  private def announce(state: TorrentState.Response.State,
                       event: String,
                       numWant: Int,
                       trackerId: Option[String]): Unit = {
    args.tracker ! Tracker.Request.Announce(
      args.torrent.announce,
      args.torrent.infoHash,
      Constant.ClientID,
      args.port,
      state.aggregated.totalUploaded,
      state.aggregated.totalDownloaded,
      state.aggregated.totalSize - state.aggregated.totalDownloaded,
      compact = true,
      event,
      numWant,
      trackerId
    )
  }

  private def scheduleTrackerAnnounceRequest(delay: FiniteDuration): Unit = {
    context.system.scheduler.scheduleOnce(delay) {
      args.torrentState ! TorrentState.Request.CurrentState
    }
  }
}
