package storrent.core

import akka.actor.{ Actor, Props }
import storrent.message._

object PrintActor {
  def props(): Props = Props(new PrintActor)
}

class PrintActor extends Actor {
  def receive = {
  	case _: FM.Write => ()
    case msg => ()
  }
}
