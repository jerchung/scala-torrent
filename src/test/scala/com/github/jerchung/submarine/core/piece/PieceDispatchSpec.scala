package com.github.jerchung.submarine.core.piece

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.EventStream
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.Torrent
import com.github.jerchung.submarine.core.peer.Peer
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.any
import org.scalatest.{BeforeAndAfterAll, FreeSpecLike}

import scala.collection.BitSet
import scala.concurrent.duration._

class PieceDispatchSpec
  extends TestKit(ActorSystem("PieceDispatchSpec"))
  with FreeSpecLike
  with BeforeAndAfterAll
  with MockitoSugar {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val duration = 500.milliseconds

  trait TestEnv {
    val testPipeline: TestProbe = TestProbe()
    val testPeer: TestProbe = TestProbe()

    private trait TestCake extends PieceDispatch.Cake { this: PieceDispatch =>
      val provider = new PiecePipeline.Provider {
        override def piecePipeline(args: PiecePipeline.Args): ActorRef = testPipeline.ref
      }
    }

    def initializeTestPieceDispatch(numPieces: Int,
                                    pieceFrequencies: Map[Int, Int] = Map(),
                                    completedPieces: BitSet = BitSet(),
                                    highPriorityPieces: BitSet = BitSet()): TestActorRef[PieceDispatch] = {
      val mockTorrent = mock[Torrent]
      val mockTorrentEvents = mock[EventStream]
      val indexedPiecesHashes = (0 until numPieces).map(i => ByteString(i)).toArray
      val actualFrequencies = (0 until numPieces).map { i =>
        if (pieceFrequencies.contains(i))
          i -> pieceFrequencies(i)
        else
          i -> 0
      }.toMap

      when(mockTorrent.numPieces).thenReturn(numPieces)

      // These properties don't really matter
      when(mockTorrent.pieceSize).thenReturn(20)
      when(mockTorrent.totalSize).thenReturn(100)
      when(mockTorrent.indexedPieceHashes).thenReturn(indexedPiecesHashes)

      when(mockTorrentEvents.subscribe(any[ActorRef], any[Class[_]])).thenReturn(true)

      val pieceDispatch: TestActorRef[PieceDispatch] =
        TestActorRef(Props(new PieceDispatch(PieceDispatch.Args(mockTorrent, mockTorrentEvents)) with TestCake))

      pieceDispatch.underlyingActor.pieceFrequencies = actualFrequencies
      pieceDispatch.underlyingActor.completedPieces = completedPieces
      pieceDispatch.underlyingActor.highPriorityPieces = highPriorityPieces

      pieceDispatch
    }
  }

  "PieceDispatch" - {
    "when receiving" -{
      "Peer.IsReady" - {
        "when there is 1 piece" - {
          "should choose the 1 piece for all free peers to download from" in new TestEnv {
            val dispatch = initializeTestPieceDispatch(1, Map(0 -> 1))
            dispatch ! Peer.Announce(testPeer.ref, Peer.IsReady(BitSet(0)))

            val message = testPeer.expectMsgClass(duration, classOf[Peer.Message.DownloadPiece])

            assert(message.index == 0, "Download piece message piece index should be 0")
          }
        }

        "when there are multiple pieces" - {
          "should choose one of the available pieces for peers to download" in new TestEnv {
            val dispatch = initializeTestPieceDispatch(3, Map(0 -> 1, 1 -> 1))
            dispatch ! Peer.Announce(testPeer.ref, Peer.IsReady(BitSet(0, 1)))

            val message = testPeer.expectMsgClass(duration, classOf[Peer.Message.DownloadPiece])

            assert(message.index == 0 || message.index == 1, "Download piece message index should be an available piece index")
          }
        }

        "when no pieces are available" - {
          "should send a NotInterested message to the peer" in new TestEnv {
            val dispatch = initializeTestPieceDispatch(1, Map(0 -> 0))
            dispatch ! Peer.Announce(testPeer.ref, Peer.IsReady(BitSet()))

            testPeer.expectMsg(duration, Peer.Message.Interested(false))
          }
        }
      }

      "Peer.Connected" - {
        "should send message with available pieces" in new TestEnv {
          val dispatch = initializeTestPieceDispatch(3, completedPieces = BitSet(0, 1))
          dispatch ! Peer.Announce(testPeer.ref, mock[Peer.Connected])

          testPeer.expectMsg(duration, Peer.Message.IHave(BitSet(0, 1)))
        }

        "should send an empty bitset if no pieces are available" in new TestEnv {
          val dispatch = initializeTestPieceDispatch(3, completedPieces = BitSet())
          dispatch ! Peer.Announce(testPeer.ref, mock[Peer.Connected])

          testPeer.expectMsg(duration, Peer.Message.IHave(BitSet()))
        }
      }

      "Peer.Disconnected" - {
        "should decrement all the pieces the peer has in pieceFrequencies" in new TestEnv {
          val dispatch = initializeTestPieceDispatch(3, pieceFrequencies = Map(0 -> 1, 1 -> 1, 2 -> 1))
          dispatch ! Peer.Announce(testPeer.ref, Peer.Disconnected(ByteString(), "", BitSet(1, 2)))

          val newFrequencies = dispatch.underlyingActor.pieceFrequencies.filter(_._2 > 0)

          assert(newFrequencies.size == 1)
          assert(newFrequencies.getOrElse(0, 0) == 1)
        }
      }

      "Peer.Available" - {
        "should increment the correct index frequencies" in new TestEnv {
          val dispatch = initializeTestPieceDispatch(3)

          assert(dispatch.underlyingActor.pieceFrequencies.size == 3)
          assert(dispatch.underlyingActor.pieceFrequencies.forall(_._2 == 0))

          dispatch ! Peer.Announce(testPeer.ref, Peer.Available(Left(1)))

          assert(dispatch.underlyingActor.pieceFrequencies.size == 3)
          assert(dispatch.underlyingActor.pieceFrequencies(1) == 1)
        }

        "should increment all the correct index frequencies" in new TestEnv {
          val dispatch = initializeTestPieceDispatch(3)

          assert(dispatch.underlyingActor.pieceFrequencies.size == 3)
          assert(dispatch.underlyingActor.pieceFrequencies.forall(_._2 == 0))

          dispatch ! Peer.Announce(testPeer.ref, Peer.Available(Right(BitSet(0, 1, 2))))

          assert(dispatch.underlyingActor.pieceFrequencies.size == 3)
          assert(dispatch.underlyingActor.pieceFrequencies.forall(_._2 == 1), "All the frequences got incremented by 1")
        }
      }

      "PiecePipeline.Done" - {
        "should add to completedPieces" in new TestEnv {
          val dispatch = initializeTestPieceDispatch(3)

          assert(dispatch.underlyingActor.completedPieces.isEmpty)

          dispatch ! PiecePipeline.Done(2, Array[Byte]())

          assert(dispatch.underlyingActor.completedPieces == BitSet(2))
        }
      }

      "PiecePipeline.Priority" - {
        "when high priority" - {
          "should add the piece index to highPriorityPieces" in new TestEnv {
            val dispatch = initializeTestPieceDispatch(3, highPriorityPieces = BitSet())

            assert(dispatch.underlyingActor.highPriorityPieces.isEmpty)

            dispatch ! PiecePipeline.Priority(1, isHigh = true)

            assert(dispatch.underlyingActor.highPriorityPieces == BitSet(1))
          }
        }

        "when not high priority" - {
          "should remove the piece index from highPriorityPieces" in new TestEnv {
            val dispatch = initializeTestPieceDispatch(3, highPriorityPieces = BitSet(1, 2))
            dispatch ! PiecePipeline.Priority(1, isHigh = false)
            assert(dispatch.underlyingActor.highPriorityPieces == BitSet(2))
          }
        }
      }
    }
  }
}
