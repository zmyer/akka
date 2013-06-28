/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import scala.concurrent.duration._
import akka.testkit.AkkaSpec
import akka.actor.Actor
import akka.event.Logging.InitializeLogger
import akka.event.Logging.LogEvent
import akka.event.Logging.LoggerInitialized
import akka.event.Logging.Error
import akka.util.Timeout

object DeprecatedEventHandlerSpec {

  case class WrappedLogEvent(event: Any)

  class TestEventHandler extends Actor {
    def receive = {
      case init: InitializeLogger ⇒
        sender ! LoggerInitialized
      case err: Error ⇒
        context.system.eventStream.publish(WrappedLogEvent(err))
      case event: LogEvent ⇒
    }
  }
}

class DeprecatedEventHandlerSpec extends AkkaSpec("""
      akka.event-handlers = ["akka.config.DeprecatedEventHandlerSpec$TestEventHandler"]
      akka.event-handler-startup-timeout = 17s
    """) {

  import DeprecatedEventHandlerSpec._

      @Test def `must use deprected event-handler properties`: Unit = {
      assertThat(system.settings.EventHandlers, equalTo(List(classOf[TestEventHandler].getName)))
      assertThat(system.settings.EventHandlerStartTimeout, equalTo(Timeout(17.seconds)))

      system.eventStream.subscribe(testActor, classOf[WrappedLogEvent])

      system.log.error("test error")
      expectMsgPF(remaining) {
        case WrappedLogEvent(Error(_, _, _, "test error")) ⇒
      }

    }
  }