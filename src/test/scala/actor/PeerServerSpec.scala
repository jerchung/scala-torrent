package org.jerchung.torrent.actor

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.io.IO
import akka.io.Tcp
import akka.testkit.TestActorRef
import akka.testkit.TestKit
import akka.testkit.TestProbe
import com.escalatesoft.subcut.inject._
import java.net.InetSocketAddress
import org.jerchung.torrent.actor.message.TorrentM
import org.jerchung.torrent.dependency.BindingKeys._
import org.scalatest._

class PeerServerSpec(_sys: ActorSystem)
    extends ActorSpec(_sys)
    with WordSpecLike {

  import NewBindingModule._

  def this() = this(ActorSystem("PeerServerSpec"))

  trait peerServer {
    val testParent = TestProbe()
    val tcpManager = TestProbe()
    implicit val binding = newBindingModule { module =>
      import module._
      bind [ActorRef] idBy TcpId toSingle tcpManager.ref
      bind [ActorRef] idBy ParentId toSingle testParent.ref
    }
    val peerServer = system.actorOf(PeerServer.props)
  }

  "The PeerServer Actor" when {

    "first initiating" should {

      "send a Bind message to the Tcp Manager" in new peerServer {
        tcpManager.expectMsgClass(classOf[Tcp.Bind])
      }
    }

    "receiving an incoming connection" should {

      "send a CreatePeer message to its parent (TorrentClient)" in new peerServer {
        val remote = new InetSocketAddress("remote", 0)
        val local = new InetSocketAddress("localhost", 0)
        peerServer ! Tcp.Connected(remote, local)
        testParent.expectMsg(TorrentM.CreatePeer(testActor, remote))
      }
    }
  }
}