package org.jerchung.torrent.actor

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.testkit.TestKit
import akka.testkit.TestProbe
import akka.util.ByteString
import org.jerchung.torrent.actor.message.BT
import scala.collection.BitSet

class TorrentProtocolSpec(_sys: ActorSystem)
    extends ActorSpec(_sys) {

  def this() = this(ActorSystem("TorrentProtocolSpec"))

  override def afterAll = {
    TestKit.shutdownActorSystem(system)
  }

  case class FixtureParam(parent: ActorRef, connection: TestProbe)

  def withFixture(test: OneArgTest) = {
    val connection = TestProbe()
    val props = TorrentProtocol.props(connection.ref)
    val protocolParent = system.actorOf(Props(new TestParent(props)))
    val fixParam = FixtureParam(protocolParent, connection)
    withFixture(test.toNoArgTest(fixParam))
  }

  "A TorrentProtocol Actor" when {

    "receiving Bytestring messages from a peer" should {

      "translate to KeepAlive" in { f =>
        val keepAlive = ByteString(0, 0, 0, 0)
        f.parent ! Tcp.Received(keepAlive)
        expectMsg(BT.KeepAliveR)
      }

      "translate to Choke" in { f =>
        val choke = ByteString(0, 0, 0, 1, 0)
        f.parent ! Tcp.Received(choke)
        expectMsg(BT.ChokeR)
      }

      "translate to Unchoke" in { f =>
        val unchoke = ByteString(0, 0, 0, 1, 1)
        f.parent ! Tcp.Received(unchoke)
        expectMsg(BT.UnchokeR)
      }

      "translate to Interested" in { f =>
        val interested = ByteString(0, 0, 0, 1, 2)
        f.parent ! Tcp.Received(interested)
        expectMsg(BT.InterestedR)
      }

      "translate to NotInterested" in { f =>
        val notInterested = ByteString(0, 0, 0, 1, 3)
        f.parent ! Tcp.Received(notInterested)
        expectMsg(BT.NotInterestedR)
      }

      "translate to Have" in { f =>
        val idx = 129
        val have = ByteString(0, 0, 0, 5, 4) ++
                   TorrentProtocol.byteStringify(4, idx)
        f.parent ! Tcp.Received(have)
        expectMsg(BT.HaveR(idx))
      }

      "translate to Request" in { f =>
        val (idx, off, len) = (10, 400, 512)
        val request = TorrentProtocol.request(idx, off, len)
        f.parent ! Tcp.Received(request)
        expectMsg(BT.RequestR(idx, off, len))
      }

      "translate to Piece" in { f =>
        val (idx, off, block) = (30, 200, ByteString(12, 4, 4, 3))
        val piece = TorrentProtocol.piece(idx, off, block)
        f.parent ! Tcp.Received(piece)
        expectMsg(BT.PieceR(idx, off, block))
      }

      "translate to Port" in { f =>
        val port = 3958
        val portBytes = TorrentProtocol.port(port)
        f.parent ! Tcp.Received(portBytes)
        expectMsg(BT.PortR(port))
      }

      "translate to Bitfield" in { f =>
        val bits = BitSet(1, 3, 8, 10, 20)
        val bitfield = TorrentProtocol.bitfield(bits, 25)
        f.parent ! Tcp.Received(bitfield)
        expectMsg(BT.BitfieldR(bits))
      }
    }

  }

}