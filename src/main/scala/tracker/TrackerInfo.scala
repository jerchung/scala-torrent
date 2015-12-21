package storrent.tracker

import akka.util.ByteString

import storrent.Constant

case class TrackerInfo(
  infoHash: Array[Byte],
  peerId: String,
  port: Int,
  uploaded: Long,
  downloaded: Long,
  left: Long,
  numWant: Int,
  compact: Int,
  event: String) {

  // For params to send to http tracker
  def toStringMap(): Map[String, String] = Map(
    "info_hash" -> urlBinaryEncode(infoHash),
    "peer_id" -> peerId,
    "port" -> port.toString,
    "uploaded" -> uploaded.toString,
    "downloaded" -> downloaded.toString,
    "left"-> left.toString,
    "numwant" -> numWant.toString,
    "compact" -> compact.toString,
    "event" -> event
  )

  // Encode all bytes in %nn format (% prepended hex form)
  private def urlBinaryEncode(bytes: Array[Byte]): String = {
    bytes.map("%" + "%02X".format(_)).mkString
  }
}
