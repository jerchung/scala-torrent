/*package org.jerchung.torrent.actor

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Scheduler
import akka.actor.Cancellable
import akka.testkit.TestActorRef
import akka.testkit.TestKit
import akka.testkit.TestProbe
import akka.testkit.ImplicitSender
import akka.util.ByteString
import scala.collection.BitSet
import org.scalatest._
import org.jerchung.torrent.actor.message.BT
import org.jerchung.torrent.actor.message.TorrentM
import org.scalatest.mock._
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.hamcrest.core._
import scala.concurrent.duration.FiniteDuration

class PeerSpec(_sys: ActorSystem)
    extends ActorSpec(_sys)
    with MockitoSugar
    with FunSpecLike {

  def this() = this(ActorSystem("PeerSpec"))

  trait PeerActor {
    val peerId = ByteString(0, 0, 2, 3, 4, 9)
    val ownId = ByteString(1, 3, 5, 7, 8, 2)
    val port = 929
    val infoHash = ByteString(10, 23, 5, 7, 19, 10, 100)
    val ip = "localhost"
    val info = PeerInfo(Some(peerId), ownId, infoHash, ip, port)
    val fileManager = TestProbe()
    val connection = TestProbe()
    val testParent = TestProbe()
    val protocolProps = Props(new MockChild(connection.ref))
    val peerProps = Props(new Peer(info, protocolProps, fileManager.ref)
      with TestParent with TestScheduler {
        val parent = testParent.ref
        val scheduler = mockScheduler
      }
    )
  }

  // Set up for a peer actor in the normal receive state
  trait ReceivePeerActor extends PeerActor {
    connection.ignoreMsg({case m: BT.Handshake => true})
    val peer = TestActorRef[Peer](peerProps)
    val real = peer.underlyingActor
    real.context.become(real.receive)
  }

  // Set up for peer in initiatedHandshake state
  trait InitHandshakePeerActor extends PeerActor {
    connection.ignoreMsg({case m: BT.Handshake => true})
    val peer = TestActorRef[Peer](peerProps)
    val real = peer.underlyingActor
    real.context.become(real.initiatedHandshake)
  }

  describe("A Peer Actor") {

    describe("when in a normal receive loop state") {

      describe("when receiving messages from TorrentClient Actor") {

        describe("when receiving Choke") {
          new ReceivePeerActor {
            it("should change amChoke to true") {
              real.amChoking = false
              peer.receive(BT.Choke)
              assert(real.amChoking == true)
            }

            it("should forward message to protocol") {
              connection.expectMsg(BT.Choke)
            }
          }
        }

        describe("when receiving Unchoke") {
          new ReceivePeerActor {
            it("should change amChoke to false") {
              real.amChoking = true
              peer.receive(BT.Unchoke)
              assert(real.amChoking == false)
            }

            it("should forward message to protocol") {
              connection.expectMsg(BT.Unchoke)
            }
          }
        }

        describe("when receiving Interested") {
          new ReceivePeerActor {
            it("should change amInterested to true") {
              real.amInterested = false
              peer.receive(BT.Interested)
              assert(real.amInterested == true)
            }

            it("should forward message to protocol") {
              connection.expectMsg(BT.Interested)
            }
          }
        }

        describe("when receiving NotInterested") {
          new ReceivePeerActor {
            it("should change amInterested to false") {
              real.amInterested = true
              peer.receive(BT.NotInterested)
              assert(real.amInterested == false)
            }

            it("should forward message to protocol") {
              connection.expectMsg(BT.NotInterested)
            }
          }
        }

        describe("when receiving Have") {
          new ReceivePeerActor {
            val idx = 29
            it("should add the given index to iHave") {
              assert(!real.iHave.contains(idx))
              peer.receive(BT.Have(idx))
              assert(real.iHave.contains(idx))
            }

            it("should forward message to protocol") {
              connection.expectMsg(BT.Have(idx))
            }
          }
        }

        describe("when receiving Bitfield") {
          new ReceivePeerActor {
            val bits = BitSet(1, 3, 9, 19)
            val numPieces = 25

            it("should set iHave to the given bitfield") {
              assert(real.iHave != bits)
              peer.receive(BT.Bitfield(bits, numPieces))
              assert(real.iHave == bits)
            }

            it("should forward message to protocol") {
              connection.expectMsg(BT.Bitfield(bits, numPieces))
            }
          }
        }

      }

      describe("when receiving messages from protocol") {

        describe("when receiving KeepAliveR") {
          new ReceivePeerActor {

            it("should set keepAlive to true") {
              real.keepAlive = false
              peer.receive(BT.KeepAliveR)
              assert(real.keepAlive == true)
            }

          }
        }

        describe("when receiving ChokeR") {
          new ReceivePeerActor {

            it("should set peerChoking to true") {
              real.peerChoking = false
              peer.receive(BT.ChokeR)
              assert(real.peerChoking == true)
            }

          }
        }

        describe("when receiving UnchokeR") {
          new ReceivePeerActor {

            it("should set peerChoking to false") {
              real.peerChoking = true
              peer.receive(BT.UnchokeR)
              assert(real.peerChoking == false)
            }

          }
        }

        describe("when receiving InterestedR") {
          new ReceivePeerActor {

            it("should set peerInterested to true") {
              real.peerInterested = false
              peer.receive(BT.InterestedR)
              assert(real.peerInterested == true)
            }

          }
        }

        describe("when receiving NotInterestedR") {
          new ReceivePeerActor {

            it("should set peerInterested to false") {
              real.peerInterested = true
              peer.receive(BT.NotInterestedR)
              assert(real.peerInterested == false)
            }

          }
        }

        describe("when receiving RequestR") {

          it("should forward message to parent if it has the piece") {
            new ReceivePeerActor {
              val (idx, off, len) = (22, 352, 400)
              real.iHave += idx
              peer.receive(BT.RequestR(idx, off, len))
              testParent.expectMsg(BT.RequestR(idx, off, len))
            }
          }

          it("should not forward message to parent if it doesn't have the piece") {
            new ReceivePeerActor {
              val (idx, off, len) = (22, 352, 400)
              peer.receive(BT.RequestR(idx, off, len))
              testParent.expectNoMsg
            }
          }

        }

        describe("when receiving HaveR") {
          new ReceivePeerActor {
            val idx = 28

            it("should add given index to peerHas") {
              real.peerHas = BitSet.empty
              real.receive(BT.HaveR(idx))
              assert(real.peerHas contains idx)
            }

            it("should send Available message to parent") {
              testParent.expectMsg(TorrentM.Available(Left(idx)))
            }

          }
        }

      }
    }

    describe("when in initiatedHandshake state") {

      describe("when receiving a valid Handshake") {

        it("should send Register message with peerId to parent") {
          new InitHandshakePeerActor {
            peer.receive(BT.Handshake(infoHash, peerId))
            testParent.expectMsg(TorrentM.Register(peerId))
          }
        }

        it("should then accept Bitfield mesages and send parent message") {
          new InitHandshakePeerActor {
            testParent.ignoreMsg({case m: TorrentM.Register => true})
            peer.receive(BT.Handshake(infoHash, peerId))
            val bitset = BitSet(0, 1, 2, 3)
            peer.receive(BT.BitfieldR(bitset))
            assert(real.peerHas == bitset)
            testParent.expectMsg(TorrentM.Available(Right(bitset)))
          }
        }

        ignore("should set off the scheduler for the KeepAlive heartbeats") {
          new InitHandshakePeerActor {
            import real.context.dispatcher
            peer.receive(BT.Handshake(infoHash, peerId))
            verify(mockScheduler, times(2)).scheduleOnce(
              any[FiniteDuration],
              any[ActorRef],
              any[Runnable]
            )(any[scala.concurrent.ExecutionContext],
              any[ActorRef])
          }
        }
      }
    }
  }

}*/