package org.jerchung.torrent.actor

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.io.IO
import akka.io.Tcp
import akka.testkit._
import akka.util.ByteString
import com.escalatesoft.subcut.inject._
import org.jerchung.torrent.dependency.BindingKeys._
import java.net.InetSocketAddress
import org.jerchung.torrent.actor.message.TorrentM
import org.scalatest._

final class ConnectingPeerSpec(_sys: ActorSystem)
    extends ActorSpec(_sys)
    with WordSpecLike {

  import NewBindingModule._

  def this() = this(ActorSystem("ConnectingPeerSpec"))

  trait connectingPeer {
    val testParent = TestProbe()
    val tcpManager = TestProbe()
    implicit val binding = newBindingModule { module =>
      import module._
      bind [ActorRef] idBy TcpId toSingle tcpManager.ref
      bind [ActorRef] idBy ParentId toSingle testParent.ref
    }
    val remote = new InetSocketAddress("something", 0)
    val peerId = ByteString(0, 0, 0, 0)
  }

  "A Connecting Peer Actor" when {

    "first initialized" should {

      "send a Tcp.Connect message to Tcp Manager" in new connectingPeer {
        system.actorOf(ConnectingPeer.props(remote, peerId))
        tcpManager.expectMsgClass(classOf[Tcp.Connect])
      }
    }

    "connected to" should {

      "send a CreatePeer message to parent" in new connectingPeer {
        val connectingPeer = system.actorOf(ConnectingPeer.props(remote, peerId))
        connectingPeer ! Tcp.Connected(remote, remote)
        testParent.expectMsgClass(classOf[TorrentM.CreatePeer])
      }
    }

  }
}