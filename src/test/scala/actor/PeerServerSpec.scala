package org.jerchung.torrent.actor

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.io.IO
import akka.testkit.TestKit
import akka.testkit.TestProbe
import akka.testkit.TestActorRef
import java.net.InetSocketAddress
import org.jerchung.torrent.actor.message.TorrentM
import org.scalatest.fixture

class PeerServerSpec(_sys: ActorSystem)
    extends ActorSpec(_sys)
    with fixture.WordSpecLike {

  def this() = this(ActorSystem("PeerServerSpec"))

  case class FixtureParam(peerServer: TestActorRef[PeerServer], parent: TestProbe)

  def withFixture(test: OneArgTest) = {
    val manager = TestProbe()
    val testParent = TestProbe()
    manager.ignoreMsg({ case m: Tcp.Bind => true})
    val peerServerProps = Props(new PeerServer(manager.ref) with TestParent {
      val parent = testParent.ref
    })
    val peerServer = TestActorRef[PeerServer](peerServerProps)
    val fixParam = FixtureParam(peerServer, testParent)
    withFixture(test.toNoArgTest(fixParam))
  }

  "The PeerServer Actor" when {

    "receiving an incoming connection" should {

      "send a CreatePeer message to its parent (TorrentClient)" in { f =>
        val remote = new InetSocketAddress("remote", 0)
        val local = new InetSocketAddress("localhost", 0)
        f.peerServer ! Tcp.Connected(remote, local)
        f.parent.expectMsg(TorrentM.CreatePeer(testActor, remote))
      }

    }
  }
}