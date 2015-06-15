package storrent

import storrent.core._
import akka.actor.ActorSystem
import storrent.message._

object Test {
  val sys = ActorSystem("test")

  def run(fileName: String, folder: String): Unit = {
    val client = sys.actorOf(TorrentClient.props(fileName, folder))
    client ! TorrentM.Start(fileName)
  }
}
