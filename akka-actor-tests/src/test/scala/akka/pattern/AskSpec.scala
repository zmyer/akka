/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.testkit.AkkaSpec
import scala.concurrent.duration._
import scala.concurrent.Await
import akka.testkit.DefaultTimeout
import akka.util.Timeout
import scala.util.Failure
import akka.actor.{ Actor, Props, ActorRef }

class AskSpec extends AkkaSpec {

  
    @Test def `must return broken promises on DeadLetters`: Unit = {
      implicit val timeout = Timeout(5 seconds)
      val dead = system.actorFor("/system/deadLetters")
      val f = dead.ask(42)(1 second)
      assertThat(f.isCompleted, equalTo(true))
      f.value.get match {
        case Failure(_: AskTimeoutException) ⇒
        case v                               ⇒ fail(v + " was not Left(AskTimeoutException)")
      }
    }

    @Test def `must return broken promises on EmptyLocalActorRefs`: Unit = {
      implicit val timeout = Timeout(5 seconds)
      val empty = system.actorFor("unknown")
      val f = empty ? 3.14
      assertThat(f.isCompleted, equalTo(true))
      f.value.get match {
        case Failure(_: AskTimeoutException) ⇒
        case v                               ⇒ fail(v + " was not Left(AskTimeoutException)")
      }
    }

    @Test def `must return broken promises on unsupported ActorRefs`: Unit = {
      implicit val timeout = Timeout(5 seconds)
      val f = ask(null: ActorRef, 3.14)
      assertThat(f.isCompleted, equalTo(true))
      intercept[IllegalArgumentException] {
        Await.result(f, remaining)
      assertThat(}.getMessage, equalTo("Unsupported recipient ActorRef type, question not sent to [null]"))
    }

    @Test def `must return broken promises on 0 timeout`: Unit = {
      implicit val timeout = Timeout(0 seconds)
      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender ! x } }))
      val f = echo ? "foo"
      val expectedMsg = "Timeout length must not be negative, question not sent to [%s]" format echo
      intercept[IllegalArgumentException] {
        Await.result(f, remaining)
      assertThat(}.getMessage, equalTo(expectedMsg))
    }

    @Test def `must return broken promises on < 0 timeout`: Unit = {
      implicit val timeout = Timeout(-1000 seconds)
      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender ! x } }))
      val f = echo ? "foo"
      val expectedMsg = "Timeout length must not be negative, question not sent to [%s]" format echo
      intercept[IllegalArgumentException] {
        Await.result(f, remaining)
      assertThat(}.getMessage, equalTo(expectedMsg))
    }

  }