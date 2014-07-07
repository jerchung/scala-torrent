package org.jerchung.torrent.dependency

import com.escalatesoft.subcut.inject._

object BindingKeys {
  object TrackerClientId extends BindingId
  object PeerServerId extends BindingId
  object FileManagerId extends BindingId
  object PeersManagerId extends BindingId
  object PiecesManagerId extends BindingId
  object PeerRouterId extends BindingId
  object ParentId extends BindingId
  object TcpId extends BindingId
  object PieceChooserId extends BindingId
  object BlockRequestorId extends BindingId
  object FileWorkerId extends BindingId
  object PieceWorkerId extends BindingId
  object ReadAccumulatorId extends BindingId
  object WriteAccumulatorId extends BindingId
}
