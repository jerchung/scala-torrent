// package storrent.tracker

// import akka.actor.{ Actor, ActorRef, ActorLogging, Props }
// import akka.util.ByteString
// import akka.io.{ IO, UdpConnected }

// import scala.util.duration._
// import scala.util.Random

// import java.net.InetSocketAddress

// import storrent.core.Core._
// import storrent.Convert._

// object UdpTracker {
//   def props(client: ActorRef, remote: InetSocketAddress, info: TrackerInfo): Props = {
//     Props(new UdpTracker(client, remote, info) with AppUdpManager)
//   }

//   case class ScheduleRetry(m: ByteString, d: Duration)
// }

// class UdpTracker(client: ActorRef, remote: InetSocketAddress, info: TrackerInfo)
//   extends Actor with ActorLogging {
//   this: UdpManager =>

//   import context.dispatcher
//   import context.system

//   // 0x41727101980 in ByteString form (Network Big Endian)
//   val InitialConnectionId = ByteString(4, 23, 39, 16, 25, 128)

//   var retryMessage: Option[Scheduled] = None
//   var abort: Option[Scheduled] = None

//   override def preStart(): Unit = {
//     udpManager ! UdpConnected.Connect(self, remote)
//   }

//   def transitionState(r: Receive, message: ByteString, udp: ActorRef): Unit = {
//     retryMessage.foreach { _.cancel() }
//     abort.foreach { _.cancel() }
//     udp ! UdpConnected.Send(message)
//     val (c, a) = scheduleEvents(udp, message)
//     (retryMessage, abort) = (Some(c), Some(a))
//     context.become(r)
//   }

//   def receive = {
//     case UdpConnected.Connected =>
//       // Connect Request
//       val transactionId = genTransactionId
//       val action = ByteString(0, 0, 0, 0)
//       val connectRequest = InitialConnectionId ++ transactionId ++ action
//       transitionState(connectResponse(transactionId, ByteString()), connectRequest, sender)
//   }

//   def connectResponse(transactionId: ByteString, current: ByteString): Receive = {
//     case UdpConnected.Received(bytes: ByteString) =>
//       val received = current ++ bytes
//       if (received.length < 16) {
//         context.become(connectResponse(transactionId, received))
//       } else {
//         val action = received.slice(0, 4)
//         val transactionIdRes = received.slice(4, 8)
//         val connectionId = received.slice(8, 16)
//         if (transactionId == transactionIdRes && action == ByteString(0, 0, 0, 0)) {
//           val transactionId = genTransactionId
//           val action = ByteString(0, 0, 0, 1)
//           val announceRequest = connectionId ++
//                                 action ++
//                                 transactionId ++
//                                 ByteString(info.infoHash) ++
//                                 info.peerId.toByteString ++
//                                 info.downloaded.toByteString ++
//                                 info.left.toBytestring ++
//                                 info.uploaded.toByteString ++
//                                 ByteString(0, 0, 0, 0) ++
//                                 ByteString(0, 0, 0, 0) ++
//                                 Random.nextInt.toByteString // key
//           transitionState(announceResponse(transactionId, ByteString()), announceRequest, sender)
//         } else {
//           context.become(connectResponse(transactionId, received.drop(16)))
//           self ! UdpConnected.Received(ByteString())
//         }
//       }
//   }

//   def announceResponse(transactionId: ByteString, current: ByteString): Receive = {
//     case UdpConnected.Received(bytes: ByteString) =>
//       val received = current ++ bytes
//       if (received.length < 20) {
//         context.become(announceResponse(transactionId, received))
//       } else {
//         val action = received.slice(0, 4)
//         val transactionIdRes = received.slice(4, 8)
//         val interval = received.slice(8, 12)
//         val leechers = received.slice(12, 16).toInt
//         val seeders = received.slice(16, 20).toInt
//         if (transactionId == transactionIdRes && action == ByteString(0, 0, 0, 1)) {
//           context.become(announceResponsePeers(received.drop(20), leechers + seeders))
//           self ! UdpConnected.Received(ByteString())
//         }
//       }
//   }

//   def announceResponsePeers(current: ByteString, numPeers: Int): Receive = {
//     case UdpConnected.Received(bytes: ByteString) =>
//       val received = current ++ bytes
//       if (received.length < 6 * numPeers) {
//         context.become(announceResponsePeers(received, numPeers))
//       } else {
//         val peerBytes = received.take(6 * numPeers).grouped(6)
//         val peerInfos = peerBytes.foldLeft(List[(String, Int)]()) { case (peers, peer) =>
//           val (ipBytes, portBytes) = bytes.splitAt(4)
//           val ip = ipBytes.map(b => b & 0xFF).mkString(".")
//           val port = portBytes.toInt
//           (ip, port) :: peers
//         }
//       }
//   }

//   def genTransactionId(): ByteString = {
//     val buffer = new Array[Byte](4)
//     Random.nextBytes(buffer)
//     ByteString.fromArray(buffer)
//   }

//   def scheduleEvents(udp: ActorRef, message: ByteString): (Cancellable, Cancellable) = {
//     val retryDuration = 15.seconds
//     val abortDuration = 60.seconds
//     val c = system.scheduler.scheduleOnce(retryDuration) {
//       udp ! UdpConnected.Send(message)
//     }
//     val a = system.scheduler.scehduleOnce(abortDuration) {
//       context.stop(self)
//     }
//     (c, a)
//   }

// }
