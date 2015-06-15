package storrent.core

import akka.actor.Actor
import storrent.message._

class PrintActor extends Actor {
  def receive = {
  	case _: FM.Write => ()
    case msg => println(msg)
  }
}
