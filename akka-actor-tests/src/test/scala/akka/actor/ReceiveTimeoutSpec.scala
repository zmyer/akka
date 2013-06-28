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
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import java.util.concurrent.TimeoutException

class ReceiveTimeoutSpec extends AkkaSpec {

  
    @Test def `must get timeout`: Unit = {
      val timeoutLatch = TestLatch()

      val timeoutActor = system.actorOf(Props(new Actor {
        context.setReceiveTimeout(500 milliseconds)

        def receive = {
          case ReceiveTimeout ⇒ timeoutLatch.open
        }
      }))

      Await.ready(timeoutLatch, TestLatch.DefaultTimeout)
      system.stop(timeoutActor)
    }

    @Test def `must reschedule timeout after regular receive`: Unit = {
      val timeoutLatch = TestLatch()
      case object Tick

      val timeoutActor = system.actorOf(Props(new Actor {
        context.setReceiveTimeout(500 milliseconds)

        def receive = {
          case Tick           ⇒ ()
          case ReceiveTimeout ⇒ timeoutLatch.open
        }
      }))

      timeoutActor ! Tick

      Await.ready(timeoutLatch, TestLatch.DefaultTimeout)
      system.stop(timeoutActor)
    }

    @Test def `must be able to turn off timeout if desired`: Unit = {
      val count = new AtomicInteger(0)
      val timeoutLatch = TestLatch()
      case object Tick

      val timeoutActor = system.actorOf(Props(new Actor {
        context.setReceiveTimeout(500 milliseconds)

        def receive = {
          case Tick ⇒ ()
          case ReceiveTimeout ⇒
            count.incrementAndGet
            timeoutLatch.open
            context.setReceiveTimeout(Duration.Undefined)
        }
      }))

      timeoutActor ! Tick

      Await.ready(timeoutLatch, TestLatch.DefaultTimeout)
      assertThat(count.get, equalTo(1))
      system.stop(timeoutActor)
    }

    @Test def `must not receive timeout message when not specified`: Unit = {
      val timeoutLatch = TestLatch()

      val timeoutActor = system.actorOf(Props(new Actor {
        def receive = {
          case ReceiveTimeout ⇒ timeoutLatch.open
        }
      }))

      intercept[TimeoutException] { Await.ready(timeoutLatch, 1 second) }
      system.stop(timeoutActor)
    }
  }