package storrent.core

import akka.actor.{ Actor, Props }
import akka.pattern.ask
import scala.concurrent._
import scala.concurrent.duration._

object Coordinator {
  def props(): Props = {
    Props(new Coordinator)
  }

  case class Start(torrentFile: String, folder: String, port: Int)
  case object AskStatus
  case class Status(s: List[Int])
}

class Coordinator extends Actor with ActorLogging {
  import context.dispatcher
  import Coordinator._

  implicit val timeout = Timeout(5.seconds)

  var torrents = List[ActorRef]()

  def receive = {
    case Start(torrentFile, folder, port) =>
      val t = context.actorOf(TorrentClient.props(Config(torrentFile, folder, port)))
      torrents = t :: torrents

    case AskStatus =>
      val requestor = sender
      val statusesF = torrents.map { c => (c ? AskStatus).mapTo[Int] }
      Future.sequence(statusesF).foreach { case s =>
        sender ! Status(s)
      }
  }
}
