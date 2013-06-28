/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import akka.testkit._
import scala.concurrent.duration._
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.{ ActorRef, Actor, ActorSystem }
import java.util.{ Date, GregorianCalendar, TimeZone, Calendar }
import akka.serialization.SerializationExtension
import akka.event.Logging.{ Warning, LogEvent, LoggerInitialized, InitializeLogger }
import akka.util.Helpers

object LoggerSpec {

  val defaultConfig = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = "WARNING"
        loglevel = "DEBUG"
        loggers = ["akka.event.LoggerSpec$TestLogger1"]
      }
    """).withFallback(AkkaSpec.testConf)

  val noLoggingConfig = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = "OFF"
        loglevel = "OFF"
        loggers = ["akka.event.LoggerSpec$TestLogger1"]
      }
    """).withFallback(AkkaSpec.testConf)

  val multipleConfig = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = "OFF"
        loglevel = "WARNING"
        loggers = ["akka.event.LoggerSpec$TestLogger1", "akka.event.LoggerSpec$TestLogger2"]
      }
    """).withFallback(AkkaSpec.testConf)

  val ticket3165Config = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = "WARNING"
        loglevel = "DEBUG"
        loggers = ["akka.event.LoggerSpec$TestLogger1"]
        actor {
          serialize-messages = on
          serialization-bindings {
            "akka.event.Logging$LogEvent" = bytes
            "java.io.Serializable" = java
          }
        }
      }
    """).withFallback(AkkaSpec.testConf)

  case class SetTarget(ref: ActorRef, qualifier: Int)

  class TestLogger1 extends TestLogger(1)
  class TestLogger2 extends TestLogger(2)
  abstract class TestLogger(qualifier: Int) extends Actor with Logging.StdOutLogger {
    var target: Option[ActorRef] = None
    override def receive: Receive = {
      case InitializeLogger(bus) ⇒
        bus.subscribe(context.self, classOf[SetTarget])
        sender ! LoggerInitialized
      case SetTarget(ref, `qualifier`) ⇒
        target = Some(ref)
        ref ! ("OK")
      case event: LogEvent ⇒
        print(event)
        target foreach { _ ! event.message }
    }
  }
}

class LoggerSpec {

  import LoggerSpec._

  private def createSystemAndLogToBuffer(name: String, config: Config, shouldLog: Boolean) = {
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      implicit val system = ActorSystem(name, config)
      try {
        val probe = TestProbe()
        system.eventStream.publish(SetTarget(probe.ref, qualifier = 1))
        probe.expectMsg("OK")
        system.log.error("Danger! Danger!")
        // since logging is asynchronous ensure that it propagates
        if (shouldLog) {
          probe.fishForMessage(0.5.seconds.dilated) {
            case "Danger! Danger!" ⇒ true
            case _                 ⇒ false
          }
        } else {
          probe.expectNoMsg(0.5.seconds.dilated)
        }
      } finally {
        TestKit.shutdownActorSystem(system)
      }
    }
    out
  }

  
    @Test def `must log messages to standard output`: Unit = {
      val out = createSystemAndLogToBuffer("defaultLogger", defaultConfig, true)
      out.size must be > (0)
    }
  }

  
    @Test def `must not log messages to standard output`: Unit = {
      val out = createSystemAndLogToBuffer("noLogging", noLoggingConfig, false)
      assertThat(out.size, equalTo(0))
    }
  }

  
    @Test def `must use several loggers`: Unit = {
      Console.withOut(new java.io.ByteArrayOutputStream()) {
        implicit val system = ActorSystem("multipleLoggers", multipleConfig)
        try {
          val probe1 = TestProbe()
          val probe2 = TestProbe()
          system.eventStream.publish(SetTarget(probe1.ref, qualifier = 1))
          probe1.expectMsg("OK")
          system.eventStream.publish(SetTarget(probe2.ref, qualifier = 2))
          probe2.expectMsg("OK")

          system.log.warning("log it")
          probe1.expectMsg("log it")
          probe2.expectMsg("log it")
        } finally {
          TestKit.shutdownActorSystem(system)
        }
      }
    }
  }

      @Test def `must format currentTimeMillis to a valid UTC String`: Unit = {
      val timestamp = System.currentTimeMillis
      val c = new GregorianCalendar(TimeZone.getTimeZone("UTC"))
      c.setTime(new Date(timestamp))
      val hours = c.get(Calendar.HOUR_OF_DAY)
      val minutes = c.get(Calendar.MINUTE)
      val seconds = c.get(Calendar.SECOND)
      val ms = c.get(Calendar.MILLISECOND)
      assertThat(Helpers.currentTimeMillisToUTCString(timestamp), equalTo(f"$hours%02d:$minutes%02d:$seconds%02d.$ms%03dUTC"))
    }
  }

      @Test def `must not cause StackOverflowError`: Unit = {
      implicit val s = ActorSystem("foo", ticket3165Config)
      try {
        SerializationExtension(s).serialize(Warning("foo", classOf[String]))
      } finally {
        TestKit.shutdownActorSystem(s)
      }
    }
  }