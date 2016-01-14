package storrent.core

import akka.actor.{ Actor, Props, ActorRef, ActorLogging }
import akka.pattern.ask
import akka.util.ByteString
import akka.util.Timeout
import storrent.report.Reporter
import scala.concurrent._
import scala.concurrent.duration._
import storrent.peer.PeerServer
import storrent.Torrent

object ScalaTorrent {
  def props(port: Int): Props = {
    Props(new ScalaTorrent(port))
  }

  case class Start(torrentFile: String, folder: String)
  case object AskStatuses
  case object AskStatus
  case class Status(s: List[Int])
  case class OutsidePeerConnect(
    protocol: ActorRef,
    ip: String,
    port: Int,
    infoHash: ByteString,
    peerId: ByteString
  )
}

class ScalaTorrent(port: Int) extends Actor with ActorLogging {
  import context.dispatcher
  import ScalaTorrent._

  implicit val timeout = Timeout(5.seconds)

  val peerServer = context.actorOf(PeerServer.props(port, self))

  var activeTorrents = Map[ByteString, ActorRef]()

  def receive = {
    case Start(torrentFile, folder) =>
      val torrent = Torrent.fromFile(torrentFile)
      val infoHash = ByteString.fromArray(torrent.infoHash)
      if (!activeTorrents.contains(infoHash)) {
        val t = context.actorOf(TorrentClient.props(Config(torrent, folder, port)))
        activeTorrents += (infoHash -> t)
      }

    case AskStatuses =>
      val requestor = sender
      val statusesF = activeTorrents.values.map { c => (c ? AskStatus).mapTo[Int] }
      Future.sequence(statusesF).foreach { case s =>
        requestor ! Status(s.toList)
      }

    case conn @ OutsidePeerConnect(_, _, _, infoHash, _)
        if activeTorrents.contains(infoHash) =>
      activeTorrents(infoHash) ! conn

    case req @ Reporter.StatusRequest =>
      activeTorrents.values.foreach { _ ! req }
  }
}
