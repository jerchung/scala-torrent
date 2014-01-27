package org.jerchung.torrent.actor

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.io.IO
import akka.util.ByteString
import akka.testkit.TestKit
import akka.testkit.TestProbe
import akka.testkit.TestActorRef
import java.net.InetSocketAddress
import org.jerchung.torrent.actor.message.TorrentM
import org.scalatest.FunSpecLike

class ConnectingPeerSpec(_sys: ActorSystem)
    extends ActorSpec(_sys)
    with FunSpecLike {

  def this() = this(ActorSystem("ConnectingPeerSpec"))

  trait ConnectingPeerActor {
    val testParent = TestProbe()
    val manager = TestProbe()
    val remote = new InetSocketAddress("something", 0)
    val peerId = ByteString(0, 0, 0, 0)
    private val connectingProps = Props(new ConnectingPeer(remote, peerId)
        with TestParent with TestTcpManager {
      val parent = testParent.ref
      val tcpManager = manager.ref
    })
    val connectingPeer = TestActorRef[ConnectingPeer](connectingProps)
  }

  describe("A Connecting Peer Actor") {

    describe("when first intialized") {

      it("should send a Tcp.Connect message to Tcp Manager") {
        new ConnectingPeerActor {
          manager.expectMsgClass(classOf[Tcp.Connect])
        }
      }

    }

    describe("when connected to") {
      new ConnectingPeerActor {
        connectingPeer.receive(Tcp.Connected(remote, remote))
        it("should send a CreatePeer message to parent") {
          testParent.expectMsgClass(classOf[TorrentM.CreatePeer])
        }
      }
    }
  }
}