package storrent.core

import scalaj.http._
import storrent.bencode.Bencode
import storrent.message.{ TrackerM, TorrentM }
import akka.actor.{Actor, Props}
import scala.util.{ Success, Failure }
import scala.concurrent._

object TrackerClient {
  def props: Props = Props(new TrackerClient)
}

class TrackerClient extends Actor {
  import context.dispatcher

  def receive = {
    case TrackerM.Request(url, params) =>
      if (url.startsWith("http"))
        requestHttp(url, params)
      else if (url.startsWith("udp"))
        requestUdp(url, params)
      else
        throw new Exception("Invalid protocol")
    case e: Exception => throw e
  }

  // Sends request to tracker, sends response back to sender
  def requestHttp(announceUrl: String, params: Map[String, String]): Unit = {
    val requestor = sender
    // Need to manually build the query params because scalaj-http .params method
    // will urlencode the % symbols in the info_hash to %25
    val query = params.foldLeft(List[String]()) { case (q, (k, v)) =>
      s"$k=$v" :: q
    }.mkString("&")
    val req = Http(announceUrl + "?" + query).timeout(1000, 5000)
    val res = Future { req.asBytes }

    res onComplete {
      case Success(res) => requestor ! TrackerM.Response(res)
      case Failure(e) =>
        self ! new Exception("Tracker http request failed")
    }
  }

  def requestUdp(announce: String, params: Map[String, String]): Unit = {

  }

}
