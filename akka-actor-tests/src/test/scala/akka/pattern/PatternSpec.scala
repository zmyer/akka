/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.pattern

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.testkit.{ TestLatch, AkkaSpec }
import akka.actor.{ Props, Actor }
import java.util.concurrent.TimeoutException
import scala.concurrent.{ Future, Promise, Await }
import scala.concurrent.duration._

object PatternSpec {
  case class Work(duration: Duration)
  class TargetActor extends Actor {
    def receive = {
      case (testLatch: TestLatch, duration: FiniteDuration) ⇒
        Await.ready(testLatch, duration)
    }
  }
}

class PatternSpec extends AkkaSpec {
  implicit val ec = system.dispatcher
  import PatternSpec._

  
    @Test def `must provide Future for stopping an actor`: Unit = {
      val target = system.actorOf(Props[TargetActor])
      val result = gracefulStop(target, 5 seconds)
      assertThat(Await.result(result, 6 seconds), equalTo(true))
    }

    @Test def `must complete Future when actor already terminated`: Unit = {
      val target = system.actorOf(Props[TargetActor])
      Await.ready(gracefulStop(target, 5 seconds), 6 seconds)
      Await.ready(gracefulStop(target, 1 millis), 1 second)
    }

    @Test def `must complete Future with AskTimeoutException when actor not terminated within timeout`: Unit = {
      val target = system.actorOf(Props[TargetActor])
      val latch = TestLatch()
      target ! ((latch, remaining))
      intercept[AskTimeoutException] { Await.result(gracefulStop(target, 500 millis), remaining) }
      latch.open()
    }
  }

      @Test def `must be completed successfully eventually`: Unit = {
      val f = after(1 second, using = system.scheduler)(Promise.successful(5).future)

      val r = Future.firstCompletedOf(Seq(Promise[Int]().future, f))
      assertThat(Await.result(r, remaining), equalTo(5))
    }

    @Test def `must be completed abnormally eventually`: Unit = {
      val f = after(1 second, using = system.scheduler)(Promise.failed(new IllegalStateException("Mexico")).future)

      val r = Future.firstCompletedOf(Seq(Promise[Int]().future, f))
      assertThat(intercept[IllegalStateException] { Await.result(r, remaining) }.getMessage, equalTo("Mexico"))
    }
  }