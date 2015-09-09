package storrent.main

import scala.App

import akka.actor.ActorSystem
import storrent.core.TorrentClient
import storrent.core.Config

object Launcher extends App {
  val torrentFile = args(0)
  val folder = args(1)
  val port = args(2).toInt

  val system = ActorSystem("scala-torrent")

  system.actorOf(TorrentClient.props(Config(torrentFile, folder, port)))
}
