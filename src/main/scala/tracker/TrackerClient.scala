package storrent.tracker

import storrent.message.{ TrackerM, TorrentM }
import akka.actor.{Actor, Props, ActorLogging }
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._

import scala.util.{ Success, Failure }
import scala.concurrent._

object TrackerClient {
  def props: Props = Props(new TrackerClient)
}

class TrackerClient extends Actor with ActorLogging {
  import context.dispatcher

  implicit val materializer = ActorMaterializer()

  def receive = {
    case TrackerM.Request(url, trackerInfo) =>
      if (url.startsWith("http"))
        requestHttp(url, trackerInfo)
      else if (url.startsWith("udp"))
        requestUdp(url, trackerInfo)
      else
        throw new Exception("Invalid protocol")
    case e: Exception => throw e
  }

  // Sends request to tracker, sends response back to sender
  def requestHttp(announceUrl: String, trackerInfo: TrackerInfo): Unit = {
    val requestor = sender
    // Don't want to percent encode '%' to '%25' so use raw query
    val trackerParams = trackerInfo.toStringMap.toList.map { case (k, v) => s"$k=$v" }
    val uri = Uri(announceUrl).withRawQueryString(trackerParams.mkString("&"))
    val responseF: Future[HttpResponse] = Http(context.system).singleRequest(HttpRequest(uri = uri))

    log.info(s"Tracker http request: ${uri.toString}")

    val dataF = for {
      res <- responseF
      entity <- res.entity.toStrict(1000, materializer)
    } yield entity.getData

    dataF.onComplete {
      case Success(data) => requestor ! TrackerM.Response(data)
      case Failure(e) => self ! new Exception("Tracker http request failed")
    }
  }

  def requestUdp(announce: String, trackerInfo: TrackerInfo): Unit = {

  }

}
