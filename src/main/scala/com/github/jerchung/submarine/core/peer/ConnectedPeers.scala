package com.github.jerchung.submarine.core.peer

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.github.jerchung.submarine.core.base.Core

import scala.annotation.tailrec
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.Random

object ConnectedPeers {
  def props(args: Args): Props = {
    Props(new ConnectedPeers(args))
  }

  case class Args()

  trait Provider {
    def connectedPeers(args: Args): ActorRef
  }

  trait AppProvider extends Provider { this: Core.AppProvider =>
    def connectedPeers(args: Args): ActorRef = context.actorOf(ConnectedPeers.props(args))
  }

  case class Unchoke(count: Int)
  case class OldPeer(peer: ActorRef)

  /**
    * This keeps track of how much a peer has uploaded / downloaded from us.  It will be reset to 0, 0 after each
    * unchoke interval to recount from peers
    * @param downloadedFrom
    * @param uploadedTo
    */
  case class Stats(downloadedFrom: Int, uploadedTo: Int)

  case class ConnectPeer()
}

/**
  * Parent actor for connecting peers.  This actor is in charge of keeping track of the upload / download stats for peers
  * and chokes / unchokes peers on unchoke duration accordingly
  * @param args Required data for Peers
  */
class ConnectedPeers(args: ConnectedPeers.Args) extends Actor with ActorLogging {

  import context.{dispatcher, system}

  val NumUnchoked = 4
  val unchokeDelay: FiniteDuration = 10 seconds
  var unchokeCount: Int = 0
  val freshPeerDuration: FiniteDuration = 1 minute

  var peerStats: Map[ActorRef, ConnectedPeers.Stats] = Map()
  var freshPeers: Set[ActorRef] = Set()
  var interestedPeers: Set[ActorRef] = Set()
  var currentUnchoked: Set[ActorRef] = Set()

  var seeds: Set[ActorRef] = Set()

  def receive: Receive = handlePeerMessages

  def handlePeerMessages: Receive = {
    case Peer.Announce(peer, publish) =>
      publish match {
        case Peer.Connected(_) =>
          freshPeers += peer
          peerStats += (peer -> ConnectedPeers.Stats(0, 0))

          // Set this peer to an 'old peer' after 1 minute (it will no longer be chosen by the optimistic unchoke)
          system.scheduler.scheduleOnce(freshPeerDuration) {
            self ! ConnectedPeers.OldPeer(peer)
          }

        case Peer.Disconnected(_, _, peerHas) =>
          freshPeers -= sender
          peerStats -= sender
          currentUnchoked -= sender
          interestedPeers -= sender

        case Peer.Downloaded(bytes) if peerStats.contains(peer) =>
          val prevDownloaded = peerStats(peer).downloadedFrom
          peerStats += (peer -> peerStats(peer).copy(downloadedFrom = prevDownloaded + bytes))

        case Peer.Uploaded(bytes) if peerStats.contains(peer) =>
          val prevUploaded = peerStats(peer).uploadedTo
          peerStats += (peer -> peerStats(peer).copy(uploadedTo = prevUploaded + bytes))

        case Peer.Interested(isInterested) =>
          if (isInterested) {
            interestedPeers += peer
          } else {
            interestedPeers -= peer
          }

        case Peer.IsSeed =>
          seeds += sender
          freshPeers -= sender
          interestedPeers -= sender
          currentUnchoked -= sender

        case _ => ()
      }
  }

  def handleOther: Receive = {
    case ConnectedPeers.OldPeer(peer) =>
      freshPeers -= peer

    // Unchoke top NumUnchoked peers
    case ConnectedPeers.Unchoke(count) =>
      val chosen = peerStats
          .toList
          .sortBy { case (_, stats) => -stats.downloadedFrom }
          .map { case (peer, _) => peer }
          .take(NumUnchoked)
          .toSet

      val toChoke = currentUnchoked &~ chosen
      chosen.foreach { _ ! Peer.Message.Choke(false) }
      toChoke.foreach { _ ! Peer.Message.Choke(true) }

      currentUnchoked = chosen
      peerStats = peerStats.mapValues(_ => ConnectedPeers.Stats(0, 0))

      // Every 3rd time we're going to do an optimistic unchoke as well
      if (count % 3 == 0) {
        optimisticChoosePeer.foreach { optimisticPeer =>
          if (currentUnchoked.size >= NumUnchoked && !currentUnchoked.contains(optimisticPeer)) {
            val minPeer = currentUnchoked.minBy(peerStats(_).downloadedFrom)
            minPeer ! Peer.Message.Choke(true)

            currentUnchoked -= minPeer
          }

          optimisticPeer ! Peer.Message.Choke(false)
        }
      }

      scheduleUnchoke(unchokeDelay, count + 1)
  }

  /**
    * A 'newly connected' peer is 3 times as likely to be chosen to be unchoked
    * @return Option of a peer actorRef that should be unchoked
    */
  def optimisticChoosePeer: Option[ActorRef] = {
    val interestedNewPeers = freshPeers & interestedPeers
    val interestedOldPeers = interestedPeers &~ interestedNewPeers
    val interestedDenom = 3 * interestedNewPeers.size + interestedOldPeers.size
    if (interestedDenom == 0) {
      None
    } else {
      val baseProb = 1 / interestedDenom.toFloat
      val newPeerProb = 3 * baseProb
      val peerProbabilities: Vector[(ActorRef, Float)] =
        interestedNewPeers.toVector.map((peer: ActorRef) => (peer, newPeerProb)) ++
        interestedOldPeers.toVector.map((peer: ActorRef) => (peer, baseProb))
      weightedSelect(peerProbabilities)
    }
  }

  def weightedSelect(peerProbabilities: Vector[(ActorRef, Float)]): Option[ActorRef] = {

    @tailrec
    def selectHelper(
        peerProbabilities: Vector[(ActorRef, Float)],
        cutoff: Float): Option[ActorRef] = {
      if (peerProbabilities.isEmpty) {
        None
      } else {
        val (peer, probability) = peerProbabilities.head
        if (cutoff < probability)
          Some(peer)
        else
          selectHelper(peerProbabilities.tail, cutoff - probability)
      }
    }

    selectHelper(peerProbabilities, Random.nextFloat)
  }

  def scheduleUnchoke(delay: FiniteDuration, unchokeCount: Int): Unit = {
    system.scheduler.scheduleOnce(delay) {
      self ! ConnectedPeers.Unchoke(unchokeCount)
    }
  }
}
