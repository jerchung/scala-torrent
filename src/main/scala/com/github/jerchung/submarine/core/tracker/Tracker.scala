package com.github.jerchung.submarine.core.tracker

import akka.actor.{Actor, ActorRef, Props}
import akka.event.EventStream
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.Core
import com.github.jerchung.submarine.core.base.Core.HttpService
import com.github.jerchung.submarine.core.bencode.Bencode
import com.github.jerchung.submarine.core.implicits.Convert._

import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.util.Success

object Tracker {
  def props(args: Args): Props = Props(new Tracker(args) with Core.AppHttpService)

  case class Args(torrentEvents: EventStream)

  trait Provider extends Core.Cake#Provider {
    def tracker(args: Args): ActorRef
  }

  trait AppProvider extends Provider {
    def tracker(args: Args): ActorRef =
      context.actorOf(Tracker.props(args: Args))
  }

  sealed trait Request {
    def url: String
  }

  object Request {
    case class Announce(url: String,
                        infoHash: Seq[Byte],
                        peerId: String,
                        port: Int,
                        uploaded: Int,
                        downloaded: Int,
                        left: Int,
                        compact: Boolean,
                        event: String,
                        numWant: Int,
                        trackerId: Option[String]) extends Request

    // TODO(jerry) - Scrape Request
  }

  sealed trait Response
  object Response {
    case class Announce(interval: FiniteDuration,
                        minInterval: Option[FiniteDuration],
                        trackerId: Option[String],
                        numComplete: Int,
                        numIncomplete: Int,
                        // (ip, port, Option[peer_id])
                        peers: List[(String, Int, Option[ByteString])]) extends Response

    case class Error(message: String) extends Response
  }
}

class Tracker(args: Tracker.Args) extends Actor { this: HttpService =>

  implicit val materializer = ActorMaterializer()

  def receive: Receive = {
    case request :Tracker.Request.Announce =>
      if (request.url.startsWith("http")) {
        requestHttp(request)
      }

    case e: Exception => throw e
  }

  private def requestHttp(announce: Tracker.Request.Announce): Unit = {
    val announceUrl = announce.url
    var params: Map[String, String] = Map(
      "info_hash" -> announce.infoHash.map("%" + "%02X".format(_)).mkString,
      "peer_id" -> announce.peerId,
      "port" -> announce.port.toString,
      "uploaded" -> announce.uploaded.toString,
      "downloaded" -> announce.downloaded.toString,
      "left" -> announce.left.toString,
      "numwant" -> announce.numWant.toString,
      "compact" -> (if (announce.compact) "1" else "0"),
      "event" -> announce.event
    )

    if (announce.trackerId.isDefined) {
      params += ("trackerid" -> announce.trackerId.get)
    }

    val request = HttpRequest(uri = Uri(announceUrl).withQuery(Uri.Query(params)))
    val responseF: Future[HttpResponse] = http.singleRequest(request)

    responseF.onComplete {
      case Success(res) if res.status == StatusCodes.OK =>
        res
          .entity
          .dataBytes
          .runFold(ByteString()){ _ ++ _ }
          .foreach { body =>
            args.torrentEvents.publish(parseHttpResponse(body))
          }
      case _ =>
        self ! new Exception("Woop u failed")
    }
  }

  private def parseHttpResponse(bytes: ByteString): Tracker.Response = {
    val decoded: Map[String, Any] = Bencode.decode(bytes.toList)

    if (decoded.contains("failure reason")) {
      Tracker.Response.Error(decoded("failure reason").asInstanceOf[ByteString].toChars)
    } else {
      val peers: List[(String, Int, Option[ByteString])] = decoded("peers") match {
        case entries: List[_] =>
          parsePeers(entries.asInstanceOf[List[Map[String, Any]]])
        case entries: ByteString =>
          parsePeers(entries)
      }

      Tracker.Response.Announce(
        interval = decoded("interval").asInstanceOf[Int].seconds,
        minInterval = decoded.get("min interval").map(_.asInstanceOf[Int].seconds),
        trackerId = decoded.get("tracker id").map(_.asInstanceOf[ByteString].toChars),
        numComplete = decoded("complete").asInstanceOf[Int],
        numIncomplete = decoded("incomplete").asInstanceOf[Int],
        peers = peers
      )
    }
  }

  private def parsePeers(peers: List[Map[String, Any]]): List[(String, Int, Option[ByteString])] = {
    peers.map { p =>
      val ip = p("ip").asInstanceOf[ByteString].toChars
      val port = p("port").asInstanceOf[Int]
      val peerId = p("peer id").asInstanceOf[ByteString]
      (ip ,port, Some(peerId))
    }
  }

  private def parsePeers(peers: ByteString): List[(String, Int, Option[ByteString])] = {
    peers.grouped(6).toList.map  { bytes =>
      val (ipBytes, portBytes) = bytes.splitAt(4)
      val ip = ipBytes.map(b => b & 0xFF).mkString(".")
      val port = portBytes.toInt
      (ip, port, None)
    }
  }

  def requestUdp(announce: String, params: Map[String, String]): Unit = {
  }

}
