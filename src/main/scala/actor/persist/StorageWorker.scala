package org.jerchung.torrent.actor.persist

import akka.actor.Actor
import akka.actor.ActorRef
import akka.util.ByteString

/*
 * Storage worker trait is shared by FileReader and FileWriter in finding
 * affected files that should be read / written when an index / offset is
 * provided
 */
trait StorageWorker {

  def read(index: Int, offset: Int): ByteString

  def write(index: Int, offset: Int, block: ByteString): Int
}