package storrent.file

import akka.actor._
import akka.util.ByteString
import java.nio.ByteBuffer
import java.security.MessageDigest
import storrent.file.{ FileWorker => FW }

object PieceWorker {
  def props(fileWorker: ActorRef, index: Int, piece: Piece): Props = {
    Props(new PieceWorker(fileWorker, index, piece))
  }

  // Used to store info about requests that came in from various peers
  case class BlockWrite(offset: Int, block: ByteString, peer: ActorRef)
  case object ClearPieceData
  case class BlockWriteDone(
    index: Int,
    totalOffset: Int,
    state: PieceState,
    peer: ActorRef,
    dataOption: Option[Array[Byte]]
  )
}

// In charge of inserting parts of pieces in a thread-safe manner
class PieceWorker(
    fileWorker: ActorRef,
    index: Int,
    piece: Piece)
  extends Actor {

  import PieceWorker._

  val totalOffset = piece.offset
  val pieceSize = piece.size
  val hash = piece.hash

  lazy val bytes = ByteBuffer.allocate(pieceSize)
  var bytesWritten = 0
  val md = MessageDigest.getInstance("SHA-1")

  def receive = {
    case msg: FW.Read =>
      fileWorker forward msg

    case BlockWrite(off, block, peer) =>
      bytesWritten += insertBlock(off, block)
      val state = if (isFilled && hashMatches)
        Done
      else if (isFilled)
        Invalid
      else
        Unfinished
      val dataOption = state match {
        case Done =>
          val data = new Array[Byte](pieceSize)
          // Must copy because clearing ByteBuffer will also clear the array
          bytes.rewind()
          bytes.get(data)
          bytes.clear()
          md.reset()
          Some(data)
        case Invalid =>
          bytes.clear()
          md.reset()
          None
        case _ => None
      }
      sender ! BlockWriteDone(index, totalOffset, state, peer, dataOption)

    case ClearPieceData =>
      bytes.clear()
      bytesWritten = 0
  }

  // Put block into buffer at offset then update the number of bytes written
  def insertBlock(offset: Int, block: ByteString): Int = {
    val byteArray = block.toArray
    val numBytes = byteArray.length
    bytes.position(offset)
    bytes.put(byteArray)
    numBytes
  }

  def isFilled(): Boolean = {
    bytesWritten == pieceSize
  }

  def hashMatches(): Boolean = {
    val sha1Hash = {
      bytes.position(0)
      md.update(bytes)
      md.digest
    }
    sha1Hash.sameElements(hash)
  }


}
