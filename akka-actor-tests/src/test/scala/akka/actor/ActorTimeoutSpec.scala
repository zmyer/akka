/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import scala.concurrent.duration._
import akka.testkit._
import akka.testkit.TestEvent._
import scala.concurrent.Await
import akka.util.Timeout
import akka.pattern.{ ask, AskTimeoutException }

class ActorTimeoutSpec extends AkkaSpec {

  val testTimeout = 200.millis.dilated
  val leeway = 500.millis.dilated

  system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*unhandled message from.*hallo")))

  
    @Test def `must use implicitly supplied timeout`: Unit = {
      implicit val timeout = Timeout(testTimeout)
      val echo = system.actorOf(Props.empty)
      val f = (echo ? "hallo")
      intercept[AskTimeoutException] { Await.result(f, testTimeout + leeway) }
    }

    @Test def `must use explicitly supplied timeout`: Unit = {
      val echo = system.actorOf(Props.empty)
      val f = echo.?("hallo")(testTimeout)
      intercept[AskTimeoutException] { Await.result(f, testTimeout + leeway) }
    }
  }