package org.jerchung.torrent.actor

import akka.actor.{Actor, ActorRef, Props}
import java.io.RandomAccessFile

object BlockManager {
  def props: Props = {
    Props(classOf[BlockManager])
  }
}

class BlockManager(size: Int) extends Actor {

  def receive = {
    case Write(index, offset, block) =>
  }
}