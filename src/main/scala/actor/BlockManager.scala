package org.jerchung.torrent.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.util.ByteString
import java.security.MessageDigest
import java.io.RandomAccessFile

object BlockManager {
  def props: Props(size: Int, numBlocks: Int, pieces: ByteString) = {
    Props(classOf[BlockManager], size, numBlocks, pieces)
  }
}

class BlockManager(size: Int, numBlocks: Int, pieces: ByteString) extends Actor {

  // Hashes of each piece for later comparison
  val pieceHashes: Array[Array[Byte]] = pieces.grouped(20).map(_.toArray).toArray

  // buffer to hold pieces that are being downloaded piece by piece
  var inProgress = Map[Int, Map[Int, ByteString]]().withDefaultValue(Map.empty)

  def receive = {
    case Write(index, offset, block) =>
  }

  def sha1(bytes: ByteString): Array[Byte] = {
    MessageDigest.getInstance("SHA-1").digest(bytes)
  }

  // Check if piece is done and write it to disk
  def checkComplete(buffer: Map[Int, ByteString]) = {
    if (buffer.length == numBlocks) {

    }
  }

}