package org.jerchung.torrent.actor

import dispatch._, Defaults._
import org.jerchung.torrent.bencode.Bencode
import org.jerchung.torrent.actor.message.{ TrackerM, TorrentM }
import akka.actor.{Actor, Props}
import scala.util.{ Success, Failure }

object TrackerClient {
  def props: Props = Props(classOf[TrackerClient])
}

class TrackerClient(val announceUrl: String) extends Actor {

  def receive = {
    case TrackerM.Request(a, r) => request(a, r)
  }

  // Sends request to tracker, sends response back to sender
  def request(announceUrl: String, request: Map[String, Any]): Unit = {
    val requestor = sender
    val neededParams = List[String]("info_hash", "peer_id", "port", "uploaded",
      "downloaded", "left", "compact", "no_peer_id", "event")
    val optionalParams = List[String]("ip", "numwant", "key", "trackerid")
    val requestParams = neededParams ++ optionalParams
    neededParams.foreach { k =>
      if (!request.contains(k)) {
        throw new Exception(s"Tracker request params must have value ${k}")
      }
    }

    val requestVars = requestParams.foldLeft(List[String]()) {
      case (vars, k) if request.contains(k) =>
        s"""${k}=${request("k")}""" :: vars
      case (vars, k) => vars
    }.mkString("&")
    val requestUrl = s"${announceUrl}?${requestVars}"

    val req = url(requestUrl)
    val resp = Http(req OK as.String)

    resp onComplete {
      case Success(s) => requestor ! TorrentM.TrackerR(s)
      case Failure(e) => self ! new Exception("Tracker http request failed")
    }
  }

}