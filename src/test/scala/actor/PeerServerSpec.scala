package org.jerchung.torrent.actor

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.io.IO
import akka.testkit.TestKit
import akka.testkit.TestProbe
import java.net.InetSocketAddress
import org.jerchung.torrent.actor.message.TorrentM

class PeerServerSpec(_sys: ActorSystem)
    extends ParentActorSpec(_sys) {

  def this() = this(ActorSystem("PeerServerSpec"))

  override def afterAll = {
    TestKit.shutdownActorSystem(system)
  }

  case class FixtureParam(parent: ActorRef)

  def withFixture(test: OneArgTest) = {
    val manager = TestProbe()
    manager.ignoreMsg({ case m: Tcp.Bind => true})
    val props = PeerServer.props(manager.ref)
    val peerServerParent = system.actorOf(Props(new TestParent(props)))
    val fixParam = FixtureParam(peerServerParent)
    withFixture(test.toNoArgTest(fixParam))
  }

  "The PeerServer Actor" when {

    "receiving an incoming connection" should {

      "send a CreatePeer message its parent (TorrentClient)" in { f =>
        val remote = new InetSocketAddress("remote", 0)
        val local = new InetSocketAddress("localhost", 0)
        val pretendPeer = TestProbe()
        pretendPeer.send(f.parent, Tcp.Connected(remote, local))
        expectMsg(TorrentM.CreatePeer(pretendPeer.ref, remote))
      }

    }
  }
}