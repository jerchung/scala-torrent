package storrent.api

import akka.actor.{ActorSystem, ActorRef}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.stream.ActorMaterializer
import spray.json.DefaultJsonProtocol

import storrent.core.ScalaTorrent
import storrent.report.Reporter

trait Protocols extends DefaultJsonProtocol {
  implicit val startTorrentFormat = jsonFormat(ScalaTorrent.Start.apply, "torrent_file", "folder")
}

trait Service extends Protocols {
  def scalaTorrent: ActorRef

  val route = {
    path("torrent") {
      (post & entity(as[ScalaTorrent.Start])) { start =>
        scalaTorrent ! start
        complete("We done")
      } ~
      get {
        scalaTorrent ! Reporter.StatusRequest
        complete("Status get")
      }
    }
  }
}

object Server extends App with Service {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  val scalaTorrent = system.actorOf(ScalaTorrent.props(54283))

  Http().bindAndHandle(Route.handlerFlow(route), "0.0.0.0", 9000)
}