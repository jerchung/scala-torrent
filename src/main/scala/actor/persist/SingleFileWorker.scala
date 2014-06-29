package org.jerchung.torrent.actor.persist

import akka.actor.Actor
import akka.actor.ActorRef
import akka.util.ByteString
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import org.jerchung.torrent.actor.message.BT
import org.jerchung.torrent.actor.message.FM

object SingleFileWorker {
  def props(name: String, pieceSize: Int, size: Int): Props = {
    Props(new SingleFileWorker(name, pieceSize, size))
  }
}

/*
 * Handles Read / Write operations for a single file and sends relevant messages
 * back to FileManager and Peer.  Parent *MUST* be FileManager.  Gets forwarded
 * messages from Peer through FileManager so sender is peer reference
 */
class SingleFileWorker(
    name: String,
    pieceSize: Int,
    size: Int)
    extends Actor {

  val raf = new RandomAccessFile(name, "rw")
  val fc: FileChannel = raf.getChannel

  def receive = {

    // The offset in this is message is the offset within the file this actor
    // is referencing
    case FW.Read(off, length) =>
      val block = read(off, length)
      sender ! BT.Piece(idx, off, block)

    case FM.Write(idx, off, block) =>
      write(idx, off, block)

  }

  override def read(offset: Int, length: Int): ByteString = {
    val buffer = ByteBuffer.allocate(length)
    fc.position(offset)
    fc.read(buffer)
    ByteString(buffer)
  }

  override def write(offset: Int, src: ByteString): Int = {
    val buffer = src.asByteBuffer
    fc.position(offset)
    fc.write(buffer)
  }

}