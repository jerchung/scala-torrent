package org.jerchung.torrent.actor

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.testkit.TestKit
import akka.testkit.TestProbe
import akka.testkit.TestActorRef
import akka.util.ByteString
import org.jerchung.torrent.actor.message.BT
import scala.collection.BitSet
import org.scalatest.fixture

class TorrentProtocolSpec(_sys: ActorSystem)
    extends ActorSpec(_sys)
    with fixture.WordSpecLike {

  import TorrentProtocol.byteStringify

  def this() = this(ActorSystem("TorrentProtocolSpec"))

  case class FixtureParam(
    protocol: TestActorRef[TorrentProtocol],
    parent: TestProbe,
    connection: TestProbe
  )

  def withFixture(test: OneArgTest) = {
    val connection = TestProbe()
    val testParent = TestProbe()
    connection.ignoreMsg({ case m: Tcp.Register => true })
    val props = Props(new TorrentProtocol(connection.ref) with TestParent {
      val parent = testParent.ref
    })
    val protocol = TestActorRef[TorrentProtocol](props)
    val fixParam = FixtureParam(protocol, testParent, connection)
    withFixture(test.toNoArgTest(fixParam))
  }

  "A TorrentProtocol Actor" when {

    "receiving valid Bytestring messages from a peer" should {

      "translate to KeepAlive" in { f =>
        val keepAlive = ByteString(0, 0, 0, 0)
        f.protocol ! Tcp.Received(keepAlive)
        f.parent.expectMsg(BT.KeepAliveR)
      }

      "translate to Choke" in { f =>
        val choke = ByteString(0, 0, 0, 1, 0)
        f.protocol ! Tcp.Received(choke)
        f.parent.expectMsg(BT.ChokeR)
      }

      "translate to Unchoke" in { f =>
        val unchoke = ByteString(0, 0, 0, 1, 1)
        f.protocol ! Tcp.Received(unchoke)
        f.parent.expectMsg(BT.UnchokeR)
      }

      "translate to Interested" in { f =>
        val interested = ByteString(0, 0, 0, 1, 2)
        f.protocol ! Tcp.Received(interested)
        f.parent.expectMsg(BT.InterestedR)
      }

      "translate to NotInterested" in { f =>
        val notInterested = ByteString(0, 0, 0, 1, 3)
        f.protocol ! Tcp.Received(notInterested)
        f.parent.expectMsg(BT.NotInterestedR)
      }

      "translate to Have" in { f =>
        val idx = 129
        val have = ByteString(0, 0, 0, 5, 4) ++ byteStringify(4, idx)
        f.protocol ! Tcp.Received(have)
        f.parent.expectMsg(BT.HaveR(idx))
      }

      "translate to Request" in { f =>
        val (idx, off, len) = (10, 400, 512)
        val request = TorrentProtocol.request(idx, off, len)
        f.protocol ! Tcp.Received(request)
        f.parent.expectMsg(BT.RequestR(idx, off, len))
      }

      "translate to Piece" in { f =>
        val (idx, off, block) = (30, 200, ByteString(12, 4, 4, 3))
        val piece = TorrentProtocol.piece(idx, off, block)
        f.protocol ! Tcp.Received(piece)
        f.parent.expectMsg(BT.PieceR(idx, off, block))
      }

      "translate to Port" in { f =>
        val port = 3958
        val portBytes = TorrentProtocol.port(port)
        f.protocol ! Tcp.Received(portBytes)
        f.parent.expectMsg(BT.PortR(port))
      }

      "translate to Bitfield" in { f =>
        val bits = BitSet(0, 1, 3, 8, 10, 20)
        val bitfield = TorrentProtocol.bitfield(bits, 25)
        f.protocol ! Tcp.Received(bitfield)
        f.parent.expectMsg(BT.BitfieldR(bits))
      }

      "translate to Handshake" in { f =>
        val info = ByteString(0, 29, 30, 4, 50, 29, 9, 0, 2, 3, 11, 2, 3, 4, 5, 6, 7, 8, 9, 0)
        val id = ByteString(1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
        val handshake = TorrentProtocol.handshake(info, id)
        f.protocol ! Tcp.Received(handshake)
        f.parent.expectMsg(BT.HandshakeR(info, id))
      }
    }

  "Sending a TCP Wire Message to a peer" should {

    "correctly translate KeepAlive to ByteString" in { f =>
      f.protocol ! BT.KeepAlive
      f.connection.expectMsg(Tcp.Write(TorrentProtocol.keepAlive))
    }

    "correctly translate Choke to ByteString" in { f =>
      f.protocol ! BT.Choke
      f.connection.expectMsg(Tcp.Write(TorrentProtocol.choke))
    }

    "correctly translate Unchoke to ByteString" in { f =>
      f.protocol ! BT.Unchoke
      f.connection.expectMsg(Tcp.Write(TorrentProtocol.unchoke))
    }

    "correctly translate Interested to ByteString" in { f =>
      f.protocol ! BT.Interested
      f.connection.expectMsg(Tcp.Write(TorrentProtocol.interested))
    }

    "correctly translate NotInterested to ByteString" in { f =>
      f.protocol ! BT.NotInterested
      f.connection.expectMsg(Tcp.Write(TorrentProtocol.notInterested))
    }

    "correctly translate Have to ByteString" in { f =>
      val idx = 28
      f.protocol ! BT.Have(idx)
      f.connection.expectMsg(Tcp.Write(TorrentProtocol.have(idx)))
    }

    "correctly translate Bitfield to ByteString" in { f =>
      val (bits, numPieces) = (BitSet(10, 28, 30), 42)
      f.protocol ! BT.Bitfield(bits, numPieces)
      f.connection.expectMsg(Tcp.Write(TorrentProtocol.bitfield(bits, numPieces)))
    }

    "correctly translate Request to ByteString" in { f =>
      val (idx, off, len) = (29, 212, 400)
      f.protocol ! BT.Request(idx, off, len)
      f.connection.expectMsg(Tcp.Write(TorrentProtocol.request(idx, off, len)))
    }

    "correctly translate Piece to ByteString" in { f =>
      val (idx, off, block) = (29, 219, ByteString(10, 10, 1, 3, 5, 7, 65, 2))
      f.protocol ! BT.Piece(idx, off, block)
      f.connection.expectMsg(Tcp.Write(TorrentProtocol.piece(idx, off, block)))
    }

    "correctly translate Cancel to ByteString" in { f =>
      val (idx, off, len) = (29, 212, 400)
      f.protocol ! BT.Cancel(idx, off, len)
      f.connection.expectMsg(Tcp.Write(TorrentProtocol.cancel(idx, off, len)))
    }

    "Correctly translate Handshake to ByteString" in { f =>
      val info = ByteString(0, 29, 30, 4, 50, 29, 9, 0, 2, 3, 11, 2, 3, 4, 5, 6, 7, 8, 9, 0)
      val id = ByteString(1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
      f.protocol ! BT.Handshake(info, id)
      f.connection.expectMsg(Tcp.Write(TorrentProtocol.handshake(info, id)))
    }

  }

  }

}