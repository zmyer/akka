/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.testkit._
import scala.concurrent.duration._
import akka.actor.Actor._
import scala.concurrent.Await
import akka.pattern.{ ask, pipe }

object ForwardActorSpec {
  val ExpectedMessage = "FOO"

  def createForwardingChain(system: ActorSystem): ActorRef = {
    val replier = system.actorOf(Props(new Actor {
      def receive = { case x ⇒ sender ! x }
    }))

    def mkforwarder(forwardTo: ActorRef) = system.actorOf(Props(
      new Actor {
        def receive = { case x ⇒ forwardTo forward x }
      }))

    mkforwarder(mkforwarder(mkforwarder(replier)))
  }
}

class ForwardActorSpec extends AkkaSpec {
  import ForwardActorSpec._
  implicit val ec = system.dispatcher
  
    @Test def `must forward actor reference when invoking forward on tell`: Unit = {
      val replyTo = system.actorOf(Props(new Actor { def receive = { case ExpectedMessage ⇒ testActor ! ExpectedMessage } }))

      val chain = createForwardingChain(system)

      chain.tell(ExpectedMessage, replyTo)
      expectMsg(5 seconds, ExpectedMessage)
    }

    @Test def `must forward actor reference when invoking forward on ask`: Unit = {
      val chain = createForwardingChain(system)
      chain.ask(ExpectedMessage)(5 seconds) pipeTo testActor
      expectMsg(5 seconds, ExpectedMessage)
    }
  }