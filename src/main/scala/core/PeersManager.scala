package storrent.core

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Cancellable }
import akka.util.ByteString
import storrent.Constant
import storrent.message.{ BT, PeerM, TorrentM }
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random
import storrent.Constant
import storrent.Torrent
import storrent.peer._

object PeersManager {

  def props(state: ActorRef, torrent: Torrent): Props = {
    Props(new PeersManager(state, torrent))
  }

  case object Unchoke
  case object OptimisticUnchoke
  case class OldPeer(peer: ActorRef)
  case class Broadcast(message: Any)

  case class ConnectingPeer(
    ip: String,
    port: Int,
    peerId: Option[ByteString],
    router: ActorRef
  )
  case class ConnectedPeer(
    connection: ActorRef,
    ip: String,
    port: Int,
    peerId: Option[ByteString],
    handshake: HandshakeState,
    router: ActorRef
  )

  case class Progress(
    peerInfos: Map[ActorRef, PeerConfig],
    peerRates: Map[ActorRef, Float],
    seeds: Set[ActorRef]
  )
}

/*
 * PeersManager takes care of the logic for choking / unchoking peers at set
 * intervals.  Keeps track of download rate of peers. Peers get connected /
 * disconnected based on messages from TorrentClient
 */
class PeersManager(state: ActorRef, torrent: Torrent)
  extends Actor
  with ActorLogging {

  import context.dispatcher
  import context.system
  import PeersManager.{ Unchoke, OptimisticUnchoke, OldPeer, Broadcast, Progress}

  val unchokeFrequency: FiniteDuration = 10 seconds
  val optimisticUnchokeFrequency: FiniteDuration = 30 seconds
  val newlyConnectedDuration: FiniteDuration = 1 minute
  val NumUnchoked = 4

  // All currently connected Peers (actorRef -> Download Rate)
  // Need to differentiate between newly connected peers and old peers for unchoking
  // purposes
  var peerInfos = Map[ActorRef, PeerConfig]()
  var peerRates = Map[ActorRef, Float]()
  var newPeers = Set[ActorRef]()
  var seeds = Set[ActorRef]()
  var interestedPeers = Set[ActorRef]()
  var currentUnchoked = Set[ActorRef]()
  var peerAddresses = Set[String]()

  override def preStart(): Unit = {
    scheduleUnchoke()
    scheduleOptimisticUnchoke()
  }

  def receive = handle andThen update

  def handle: Receive = {
    case PeersManager.ConnectingPeer(ip, port, peerId, router)
        if !peerAddresses.contains(s"${ip}:${port}") =>
      context.actorOf(ConnectingPeer.props(ip, port, peerId, router, self))

    case PeersManager.ConnectedPeer(protocol, ip, port, peerId, handshake, router) =>
      val config = PeerConfig(
        peerId,
        ByteString(Constant.ID),
        ByteString.fromArray(torrent.infoHash),
        ip,
        port,
        torrent.numPieces,
        handshake
      )
      context.actorOf(Peer.props(config, protocol, router))

    case PeerM.Connected(pConfig) =>
      val peer = sender
      newPeers += peer
      peerRates += (peer -> 0f)
      system.scheduler.scheduleOnce(newlyConnectedDuration) {
        self ! OldPeer(peer)
      }
      peerInfos += (peer -> pConfig)
      peerAddresses += s"${pConfig.ip}:${pConfig.port}"

    case PeerM.Disconnected(_, peerHas, ip, port) =>
      newPeers -= sender
      peerRates -= sender
      currentUnchoked -= sender
      interestedPeers -= sender
      peerAddresses -= s"${ip}:${port}"
      seeds -= sender

    case PeerM.IsSeed =>
      seeds += sender
      newPeers -= sender
      interestedPeers -= sender
      currentUnchoked -= sender

    case PeerM.Downloaded(_, size) if peerRates.contains(sender) =>
      peerRates += (sender -> (peerRates(sender) + size.toFloat))

    case PeerM.PieceDone(i) =>
      log.debug(s"Piece at index $i done")
      broadcast(BT.Have(i))

    case BT.NotInterestedR =>
      interestedPeers -= sender

    case BT.InterestedR =>
      interestedPeers += sender

    case OldPeer(peer) =>
      newPeers -= peer

    // Unchoke top NumUnchoked peers
    case Unchoke =>
      val topKTuples = peerRates.toList
                                .filter(_._2 > 0f)
                                .sortBy(- _._2)
                                .take(NumUnchoked)
      val chosen = topKTuples.map(_._1).toSet
      val toChoke = currentUnchoked &~ chosen
      chosen foreach { _ ! BT.Unchoke }
      toChoke foreach { _ ! BT.Choke }
      currentUnchoked = chosen
      peerRates = peerRates mapValues { _ => 0f }
      scheduleUnchoke()

    // Newly connected peers are 3 times as likely to be unchoked
    // Choke worst performing peer out of currently unchoked peers and unchoke
    // the chosen peer
    case OptimisticUnchoke =>
      optimisticChoosePeer foreach { peer =>
        if (currentUnchoked.size >= NumUnchoked &&
            !currentUnchoked.contains(peer)) {
          val minPeer = currentUnchoked minBy { peerRates }
          minPeer ! BT.Choke
          currentUnchoked -= minPeer
        }
        peer ! BT.Unchoke
        currentUnchoked += peer
      }
      scheduleOptimisticUnchoke()
  }

  def update: Receive = {
    case _ => state ! Progress(peerInfos, peerRates, seeds)
  }

  def broadcast(message: Any): Unit = peerRates.keys foreach { _ ! message }

  def optimisticChoosePeer: Option[ActorRef] = {
    val interestedNewPeers = newPeers & interestedPeers
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
      peerProbabilities.headOption match {
        case Some((peer, probability)) =>
          if (cutoff < probability)
            Some(peer)
          else
            selectHelper(peerProbabilities.tail, cutoff - probability)
        case None =>
          None
      }
    }

    selectHelper(peerProbabilities, Random.nextFloat)
  }

  def scheduleUnchoke(): Unit = {
    system.scheduler.scheduleOnce(unchokeFrequency) {
      self ! Unchoke
    }
  }

  def scheduleOptimisticUnchoke(): Unit = {
    system.scheduler.scheduleOnce(optimisticUnchokeFrequency) {
      self ! OptimisticUnchoke
    }
  }

}
