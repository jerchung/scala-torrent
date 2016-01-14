package storrent.report

import akka.actor.{Props, ActorLogging, Actor}
import storrent.core.{PeersManager, PiecesManager}

import scala.collection.BitSet

object Reporter {
  def props: Props = {
    Props(new Reporter)
  }

  case object StatusRequest
  case class Status(
    pieces: PiecesManager.Progress,
    peers: PeersManager.Progress
  )
}

class Reporter extends Actor with ActorLogging {
  import Reporter._

  var piecesStatus = PiecesManager.Progress(Map(), BitSet(), BitSet())
  var peersStatus = PeersManager.Progress(Map(), Map(), Set())

  def receive = {
    case s: PiecesManager.Progress => piecesStatus = s
    case s: PeersManager.Progress => peersStatus = s
    case StatusRequest => sender ! Status(piecesStatus, peersStatus)
  }
}
