package org.jerchung.torrent.actor

import akka.actor.{Actor, ActorRef, Props}
import akka.util.ByteString
import java.security.MessageDigest
import java.io.RandomAccessFile

object FileManager {
  def props: Props(size: Int, numBlocks: Int, pieces: ByteString) = {
    Props(classOf[FileManager], size, numBlocks, pieces)
  }
}

/**
 * Doesn't just manage files, manages pieces, blocks etc. but I couldn't really
 * think of a better name for this actor
 */
class FileManager(size: Int, numBlocks: Int, pieces: ByteString) extends Actor {

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

  // Check if piece is done and flush it to disk
  def checkComplete(buffer: Map[Int, ByteString]) = {
    if (buffer.length == numBlocks) {

    }
  }

}