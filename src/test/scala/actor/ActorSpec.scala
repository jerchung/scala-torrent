package org.jerchung.torrent.actor

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.TestKit
import org.scalatest.fixture
import org.scalatest.BeforeAndAfterAll

abstract class ActorSpec(_sys: ActorSystem)
    extends TestKit(_sys)
    with fixture.WordSpecLike
    with BeforeAndAfterAll {

  /**
   * Allow for sending messages to an actor that requires a parent and then also
   * allows for exposing messages sent from the actor to a "parent"
   */
  class TestParent(props: Props) extends Actor {
    val child = context.actorOf(props)
    def receive = {
      case x if sender == child => testActor forward x
      case x => child forward x
    }
  }

}