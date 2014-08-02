package org.jerchung.torrent.actor

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.util.ByteString
import akka.testkit._
import org.jerchung.torrent.actor.message.FM
import org.jerchung.torrent.actor.message.PeerM
import org.scalatest._
import org.scalatest.mock.MockitoSugar
import scala.concurrent.duration._
import scala.language.postfixOps

final class PeerRouterSpec(_sys: ActorSystem)
    extends ActorSpec(_sys)
    with WordSpecLike
    with MockitoSugar {

  def this() = this(ActorSystem("PeerRouterSpec"))

  trait peerRouter {
    val testFileManager = TestProbe()
    val testPeersManager = TestProbe()
    val testPiecesManager = TestProbe()
    val peerRouter = system.actorOf(PeerRouter.props(
      testFileManager.ref,
      testPeersManager.ref,
      testPiecesManager.ref
    ))

    // Used for expectNoMsg
    val duration = 10 millis
  }

  "A Peer Router" should {

    "forward Disconnected message to PeersManager and PiecesManager" in new peerRouter{
      peerRouter ! mock[PeerM.Disconnected]
      testPeersManager.expectMsgClass(classOf[PeerM.Disconnected])
      testPiecesManager.expectMsgClass(classOf[PeerM.Disconnected])
      testFileManager.expectNoMsg(duration)
    }

    "forward Connected message to PeersManager and PiecesManager" in new peerRouter {
      peerRouter ! PeerM.Connected
      testPeersManager.expectMsg(PeerM.Connected)
      testPiecesManager.expectMsg(PeerM.Connected)
      testFileManager.expectNoMsg(duration)
    }

    "forward Downloaded message to PeersManager" in new peerRouter {
      peerRouter ! mock[PeerM.Downloaded]
      testPeersManager.expectMsgClass(classOf[PeerM.Downloaded])
      testPiecesManager.expectNoMsg(duration)
      testFileManager.expectNoMsg(duration)
    }

    "foward Resume message to PiecesManager" in new peerRouter {
      peerRouter ! PeerM.Resume
      testPiecesManager.expectMsg(PeerM.Resume)
      testPeersManager.expectNoMsg(duration)
      testFileManager.expectNoMsg(duration)
    }

    "forward ReadyForPiece message to PiecesManager" in new peerRouter {
      peerRouter ! mock[PeerM.ReadyForPiece]
      testPiecesManager.expectMsgClass(classOf[PeerM.ReadyForPiece])
      testPeersManager.expectNoMsg(duration)
      testFileManager.expectNoMsg(duration)
    }

    "forward ChokedOnPiece message to PiecesManager" in new peerRouter {
      peerRouter ! mock[PeerM.ChokedOnPiece]
      testPiecesManager.expectMsgClass(classOf[PeerM.ChokedOnPiece])
      testPeersManager.expectNoMsg(duration)
      testFileManager.expectNoMsg(duration)
    }

    "forward PieceAvailable message to PiecesManager" in new peerRouter {
      peerRouter ! mock[PeerM.PieceAvailable]
      testPiecesManager.expectMsgClass(classOf[PeerM.PieceAvailable])
      testPeersManager.expectNoMsg(duration)
      testFileManager.expectNoMsg(duration)
    }

    "forward PieceDone message to PiecesManager" in new peerRouter {
      peerRouter ! mock[PeerM.PieceDone]
      testPiecesManager.expectMsgClass(classOf[PeerM.PieceDone])
      testPeersManager.expectNoMsg(duration)
      testFileManager.expectNoMsg(duration)
    }

    "forward PieceInvalid message to PiecesManager" in new peerRouter {
      peerRouter ! mock[PeerM.PieceInvalid]
      testPiecesManager.expectMsgClass(classOf[PeerM.PieceInvalid])
      testPeersManager.expectNoMsg(duration)
      testFileManager.expectNoMsg(duration)
    }

    "forward Write message to FileManager" in new peerRouter {
      peerRouter ! mock[FM.Write]
      testFileManager.expectMsgClass(classOf[FM.Write])
      testPeersManager.expectNoMsg(duration)
      testPiecesManager.expectNoMsg(duration)
    }

    "forward Read message to FileManager" in new peerRouter {
      peerRouter ! mock[FM.Read]
      testFileManager.expectMsgClass(classOf[FM.Read])
      testPeersManager.expectNoMsg(duration)
      testPiecesManager.expectNoMsg(duration)
    }
  }
}