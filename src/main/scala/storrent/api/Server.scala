package storrent.api

import storrent.core.Coordinator

import akka.actor.{ Actor, ActorLogging }
import akka.pattern.ask
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import spray.routing.{ HttpService, RequestContext }
import spray.can.Http
import spray.util._
import spray.http._
import HttpMethods._
import MediaTypes._
import spray.json._

object JsonProtocol extends {
  implicit val startFormat = jsonFormat(
    Coordinator.Start.apply,
    "torrent_file",
    "folder",
    )
}

trait TorrentService extends HttpService {

  implicit val timeout = Timeout(5.seconds)
  implicit def executionContext = actorRefFactory.dispatcher

  def coordinator: ActorRef

  val route = {
    path("torrent") {
      post {
        entity(as[Coordinator.Start]) { start =>
          coordinator ! start
          complete("Eyyyy")
        }
      } ~
      get {
        val statusF = (coordinator ? Coordinator.AskStatus).mapTo[Coordinator.Status]
        onComplete(statusF) {
          case Success(status) => complete(status.toJson)
          case Failure(ex) => complete(IntervalServerError, )
        }
      }
    }
  }
}

class TorrentServer extends Actor with ActorLogging {
  val actorRefFactory = context
  val coordinator = context.actorOf(Coordinator.props)

  def receive = runRoute(route)
}
