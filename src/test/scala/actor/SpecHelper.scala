package org.jerchung.torrent.actor

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.testkit.TestKit
import akka.testkit.ImplicitSender
import org.scalatest._

/**
 * Allow for sending messages to an actor that requires a parent and then also
 * allows for exposing messages sent from the actor to a "parent"
 */
class MockParent(props: Props) extends Actor { this: TestKit =>
  val child = context.actorOf(props)
  def receive = {
    case msg if sender == child => testActor forward msg
    case msg => child forward msg
  }
}

/**
 * For classes that have a child that they send messages to, allow for the child
 * to be mocked, and then for messages sent to this child to be bounced to a
 * provided TestProbe
 */
class MockChild(probe: ActorRef) extends Actor {
  def receive = {
    case msg => probe forward msg
  }
}

/**
 * Unify all the mixins in one place for easier extending of specs
 */
abstract class ActorSpec(_sys: ActorSystem)
    extends TestKit(_sys)
    with ImplicitSender
    with Suite
    with BeforeAndAfterAll {

  override def afterAll = {
    TestKit.shutdownActorSystem(system)
  }

}