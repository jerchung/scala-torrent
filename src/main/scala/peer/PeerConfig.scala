package storrent.peer

import akka.util.ByteString

sealed trait HandshakeState
case object InitHandshake extends HandshakeState
case object WaitHandshake extends HandshakeState

// This case class encapsulates the information needed to create a peer actor
case class PeerConfig(
  peerId: Option[ByteString],
  ownId: ByteString,
  infoHash: ByteString,
  ip: String,
  port: Int,
  numPieces: Int,
  handshake: HandshakeState
)
