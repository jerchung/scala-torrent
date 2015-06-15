package storrent.file

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.ByteString
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import storrent.message.BT
import storrent.message.FM
import scala.concurrent._

object SingleFileWorker {
  def props(path: String, pieceSize: Int): Props = {
    Props(new SingleFileWorker(path, pieceSize) with AppCake)
  }

  trait Cake { this: SingleFileWorker =>
    def provider: Provider
    trait Provider {
      def raf(path: String): RandomAccessFile
    }
  }

  trait AppCake extends Cake { this: SingleFileWorker =>
    override object provider extends Provider {
      def raf(path: String) = new RandomAccessFile(path, "rw")
    }
  }
}

/*
 * Handles Read / Write operations for a single file and sends relevant messages
 * back to FileManager and Peer.  Parent *MUST* be FileManager.  Gets forwarded
 * messages from Peer through FileManager so sender is peer reference
 */
class SingleFileWorker(
    path: String,
    pieceSize: Int)
  extends Actor { this: SingleFileWorker.Cake =>
  import FileManager.{FileWorker => FW}

  val raf = provider.raf(path)
  val fc = raf.getChannel

  def receive = {

    // The offset in this is message is the offset within the file this actor
    // is referencing
    case FW.Read(idx, off, length) =>
      val block = read(off, length)
      sender ! FW.ReadDone(idx, block)

    case FW.Write(idx, off, block) =>
      val written = write(off, block)
      sender ! FW.WriteDone(idx)

  }

  def read(offset: Int, length: Int): Array[Byte] = {
    val buffer = ByteBuffer.allocate(length)
    fc.read(buffer, offset)
    buffer.array
  }

  def write(offset: Int, src: ByteString): Int = {
    val buffer = src.asByteBuffer
    fc.write(buffer, offset)
  }

}
