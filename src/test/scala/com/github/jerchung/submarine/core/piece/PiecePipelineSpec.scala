package com.github.jerchung.submarine.core.piece

import akka.actor.{ActorRef, ActorSystem, Cancellable, Scheduler, UnhandledMessage}
import akka.event.EventStream
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import akka.util.ByteString
import com.github.jerchung.submarine.core.base.Core
import com.github.jerchung.submarine.core.piece.PiecePipeline.RetryMetadata
import com.github.jerchung.submarine.core.protocol.TorrentProtocol
import org.mockito.ArgumentMatchers
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FreeSpecLike}
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.any

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class PiecePipelineSpec
  extends TestKit(ActorSystem("PiecePipelineSpec"))
  with FreeSpecLike
  with BeforeAndAfterAll
  with MockitoSugar {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val duration = 500.milliseconds

  trait TestEnv {
    val mockScheduler = mock[Scheduler]
    val mockTorrentEvents = mock[EventStream]
    val mockCancellable = mock[Cancellable]
    val mockPieceData = mock[PieceData]

    when(mockScheduler.schedule(
      any[FiniteDuration],
      any[FiniteDuration],
      any[ActorRef],
      any[Any])
      (any[ExecutionContext], any[ActorRef])).thenReturn(mockCancellable)

    when(mockCancellable.cancel()).thenReturn(true)

    val testPeer = TestProbe()
    val defaultArgs: PiecePipeline.Args = PiecePipeline.Args(
      0,
      100,
      5,
      Array[Byte](1),
      5,
      5.seconds,
      mockTorrentEvents
    )
    val args: PiecePipeline.Args = defaultArgs

    trait TestSchedulerService extends Core.SchedulerService { this: PiecePipeline =>
      val scheduler = mockScheduler
    }

    lazy val piecePipeline: TestActorRef[PiecePipeline] = {
      val pipeline: TestActorRef[PiecePipeline] = TestActorRef(new PiecePipeline(args) with TestSchedulerService)
      pipeline.underlyingActor.pieceData = mockPieceData
      pipeline
    }
  }

  "PiecePipeline" - {
    "when receiving" - {
      "PiecePipeline.Attach" - {
        "should add peer to active peers, all peers, publish priority and request blocks" in new TestEnv {
          val baseOffset = piecePipeline.underlyingActor.nextOffset
          piecePipeline ! PiecePipeline.Attach(testPeer.ref)

          assert(piecePipeline.underlyingActor.peerActives.contains(testPeer.ref))
          assert(piecePipeline.underlyingActor.allPeers.contains(testPeer.ref))

          verify(mockTorrentEvents).publish(PiecePipeline.Priority(args.pieceIndex, isHigh = false))

          for (i <- 0 until args.maxPerPeer) {
            val offset = i * args.blockSize + baseOffset
            val length = args.blockSize.min(args.pieceSize - offset)

            testPeer.expectMsg(duration, TorrentProtocol.Send.Request(args.pieceIndex, offset, length))
          }
        }

        "should schedule retries for the requested pieces" in new TestEnv {
          val baseOffset = piecePipeline.underlyingActor.nextOffset
          piecePipeline ! PiecePipeline.Attach(testPeer.ref)

          for (i <- 0 until args.maxPerPeer) {
            val offset = i * args.blockSize + baseOffset
            val length = args.blockSize.min(args.pieceSize - offset)
            val retry = PiecePipeline.Retry(offset, length)

            verify(mockScheduler).schedule(
              ArgumentMatchers.eq(args.retryInterval),
              ArgumentMatchers.eq(args.retryInterval),
              ArgumentMatchers.eq(piecePipeline),
              ArgumentMatchers.eq(retry))(any[ExecutionContext], any[ActorRef])
          }
        }
      }

      "PiecePipeline.Detach" - {
        "when a peer disconnected but hasn't started" - {
          "should not publish HighPriority message" in new TestEnv {
            piecePipeline.underlyingActor.peerActives += (testPeer.ref -> 0)

            piecePipeline ! PiecePipeline.Detach(testPeer.ref)
            assert(!piecePipeline.underlyingActor.peerActives.contains(testPeer.ref))

            verify(mockTorrentEvents, times(0)).publish(PiecePipeline.Priority(args.pieceIndex, isHigh = true))
          }
        }

        "when a peer disconnects and blocks have already started" - {
          "should publish Priority(isHigh = true) message" in new TestEnv {
            piecePipeline.underlyingActor.isStarted = true
            piecePipeline ! PiecePipeline.Detach(testPeer.ref)

            verify(mockTorrentEvents).publish(PiecePipeline.Priority(args.pieceIndex, isHigh = true))
          }
        }
      }

      "TorrentProtocol.Reply.Piece" - {
        "when the piece block input is valid (was actually requested)" - {
          trait RequestedPieceReply extends TestEnv {
            def preSend(): Unit = {}
            val incomingOffset = 5
            val piece = TorrentProtocol.Reply.Piece(
              args.pieceIndex,
              incomingOffset,
              ByteString.fromArray(Array.fill[Byte](args.blockSize)(0)))

            // Need to simulate that this piece was actually requested so that it accepts the piece
            val mockRetryCancellable = mock[Cancellable]
            piecePipeline.underlyingActor.peerActives = Map(testPeer.ref -> 1)
            piecePipeline.underlyingActor.retries +=
              ((piece.offset, piece.block.size) -> RetryMetadata(Set(testPeer.ref), mockRetryCancellable))

            // Explicitly set nextOffset so we know what message is going to be sent to the peer to test for
            val nextOffset = 10
            piecePipeline.underlyingActor.nextOffset = nextOffset
          }

          "should cancel the retries for the incoming piece" in new RequestedPieceReply {
            when(mockPieceData.update(any[Int], any[ByteString])).thenReturn(mock[PieceData.Incomplete])
            piecePipeline.tell(piece, testPeer.ref)
            verify(mockRetryCancellable, times(1)).cancel()
          }

          "when the piece is still incomplete" - {
            trait IncompletePiece extends RequestedPieceReply {
              when(mockPieceData.update(any[Int], any[ByteString])).thenReturn(mock[PieceData.Incomplete])
              preSend()
              piecePipeline.tell(piece, testPeer.ref)
            }

            "should request the next piece" in new IncompletePiece {
              override def preSend(): Unit = {
                testPeer.ignoreMsg { case _: TorrentProtocol.Send.Cancel => true }
              }
              testPeer.expectMsg(TorrentProtocol.Send.Request(args.pieceIndex, nextOffset, args.blockSize))
            }

            "should schedule the next retry" in new IncompletePiece {
              verify(mockScheduler).schedule(
                ArgumentMatchers.eq(args.retryInterval),
                ArgumentMatchers.eq(args.retryInterval),
                ArgumentMatchers.eq(piecePipeline),
                ArgumentMatchers.eq(PiecePipeline.Retry(nextOffset, args.blockSize)))(any[ExecutionContext], any[ActorRef])
            }

            "should send Cancel requests to all the peers that downloaded the piece" in new IncompletePiece {
              override def preSend(): Unit = {
                testPeer.ignoreMsg { case _: TorrentProtocol.Send.Request => true }
              }
              testPeer.expectMsg(TorrentProtocol.Send.Cancel(args.pieceIndex, piece.offset, piece.block.size))
            }
          }

          "when the piece gets completed" - {
            trait CompletePiece extends RequestedPieceReply {
              val completePiece = PieceData.Complete(args.pieceIndex, Array[Byte](1, 1, 1, 1))
              when(mockPieceData.update(any[Int], any[ByteString])).thenReturn(completePiece)
            }

            "should publish PiecePipeline.Done message to torrentEvents" in new CompletePiece {
              piecePipeline.tell(piece, testPeer.ref)
              verify(mockTorrentEvents).publish(PiecePipeline.Done(args.pieceIndex, completePiece.piece))
            }

            "should end the pipeline actor" in new CompletePiece {
              piecePipeline.tell(piece, testPeer.ref)
              assert(piecePipeline.underlying.isTerminated)
            }

            "should cancel all remaining retry tasks (if any)" in new CompletePiece {
              val secondaryCancellable = mock[Cancellable]
              when(secondaryCancellable.cancel()).thenReturn(true)
              val secondaryTestPeer = TestProbe()
              piecePipeline.underlyingActor.retries +=
                ((1, 1) -> RetryMetadata(Set(secondaryTestPeer.ref), secondaryCancellable))

              piecePipeline.tell(piece, testPeer.ref)

              verify(secondaryCancellable).cancel()
              secondaryTestPeer.expectMsgClass(classOf[TorrentProtocol.Send.Cancel])
            }
          }

          "when the piece is updated to invalid" - {
            trait InvalidPiece extends RequestedPieceReply {
              val invalidPiece = PieceData.Invalid(args.pieceIndex)
              when(mockPieceData.update(any[Int], any[ByteString])).thenReturn(invalidPiece)
            }

            "should cancel all remaining tasks (if any)" in new InvalidPiece {
              val secondaryCancellable = mock[Cancellable]
              when(secondaryCancellable.cancel()).thenReturn(true)
              val secondaryTestPeer = TestProbe()
              piecePipeline.underlyingActor.retries +=
                ((1, 1) -> RetryMetadata(Set(secondaryTestPeer.ref), secondaryCancellable))

              piecePipeline.tell(piece, testPeer.ref)

              verify(secondaryCancellable).cancel()
              secondaryTestPeer.expectMsgClass(classOf[TorrentProtocol.Send.Cancel])
            }

            //TODO - probably add some logic to send invalid messages to affected peers
          }
        }

        "when an incoming piece block was not requested (or from a non connected peer)" - {
          "should not process the message" in new TestEnv {
            system.eventStream.subscribe(testActor, classOf[UnhandledMessage])
            piecePipeline ! TorrentProtocol.Send.Piece(1, 1, ByteString())

            expectMsgClass(classOf[UnhandledMessage])
          }
        }
      }

      "PiecePipeline.Retry" - {
        "when retry is for a valid offset / length" - {
          trait ValidRetry extends TestEnv {
            val (existingOffset, existingLength) = (10, args.blockSize)
            piecePipeline.underlyingActor.retries = Map(
              (existingOffset, existingLength) -> RetryMetadata(Set(testPeer.ref), mockCancellable)
            )
          }

          "when there are new peers connected with free request slots" - {
            trait FreePeers extends ValidRetry {
              val newPeer = TestProbe()
              piecePipeline.underlyingActor.peerActives += newPeer.ref -> 0
              piecePipeline ! PiecePipeline.Retry(existingOffset, existingLength)
            }

            "should send a request for the offset / length to the peer" in new FreePeers {
              newPeer.expectMsg(TorrentProtocol.Send.Request(args.pieceIndex, existingOffset, existingLength))
            }

            "should add the new peer to the set of peers that have been requested from for this (offset, length)" in new FreePeers {
              assert(piecePipeline.underlyingActor.retries((existingOffset, existingLength)).triedPeers.contains(newPeer.ref))
            }

            "should increment the active count of the peer that got assigned" in new FreePeers {
              assert(piecePipeline.underlyingActor.peerActives(newPeer.ref) == 1)
            }
          }

          "when there are no peers connected" - {
            "should not change any state" in new ValidRetry {
              val oldRetries = piecePipeline.underlyingActor.retries
              val oldPeerActives = piecePipeline.underlyingActor.peerActives
              val oldAllPeers = piecePipeline.underlyingActor.allPeers

              piecePipeline ! PiecePipeline.Retry(existingOffset, existingLength)

              assert(piecePipeline.underlyingActor.retries == oldRetries)
              assert(piecePipeline.underlyingActor.peerActives == oldPeerActives)
              assert(piecePipeline.underlyingActor.allPeers == oldAllPeers)
            }
          }

          "when all peers are full" - {
            "should not change any state" in new ValidRetry {
              val otherPeer = TestProbe()
              piecePipeline.underlyingActor.peerActives = Map(
                testPeer.ref -> args.maxPerPeer,
                otherPeer.ref -> args.maxPerPeer
              )

              val oldRetries = piecePipeline.underlyingActor.retries
              val oldPeerActives = piecePipeline.underlyingActor.peerActives
              val oldAllPeers = piecePipeline.underlyingActor.allPeers

              piecePipeline ! PiecePipeline.Retry(existingOffset, existingLength)

              assert(piecePipeline.underlyingActor.retries == oldRetries)
              assert(piecePipeline.underlyingActor.peerActives == oldPeerActives)
              assert(piecePipeline.underlyingActor.allPeers == oldAllPeers)
            }
          }
        }
      }
    }
  }
}
