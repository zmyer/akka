/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import akka.actor.{ IOManager, ActorSystem }
import akka.event.Logging.DefaultLogger

class ConfigSpec extends AkkaSpec(ConfigFactory.defaultReference(ActorSystem.findClassLoader())) {

      @Test def `must contain all configuration properties for akka-actor that are used in code with their correct defaults`: Unit = {

      val settings = system.settings
      val config = settings.config

      {
        import config._

        assertThat(getString("akka.version"), equalTo("2.2-SNAPSHOT"))
        settings.ConfigVersion must equal("2.2-SNAPSHOT")

        assertThat(getBoolean("akka.daemonic"), equalTo(false))
        getBoolean("akka.actor.serialize-messages") must equal(false)
        assertThat(settings.SerializeAllMessages, equalTo(false))

        assertThat(getInt("akka.scheduler.ticks-per-wheel"), equalTo(512))
        getMilliseconds("akka.scheduler.tick-duration") must equal(10)
        assertThat(getString("akka.scheduler.implementation"), equalTo("akka.actor.LightArrayRevolverScheduler"))

        assertThat(getBoolean("akka.daemonic"), equalTo(false))
        assertThat(settings.Daemonicity, equalTo(false))

        assertThat(getBoolean("akka.jvm-exit-on-fatal-error"), equalTo(true))
        assertThat(settings.JvmExitOnFatalError, equalTo(true))

        assertThat(getInt("akka.actor.deployment.default.virtual-nodes-factor"), equalTo(10))
        assertThat(settings.DefaultVirtualNodesFactor, equalTo(10))

        assertThat(getMilliseconds("akka.actor.unstarted-push-timeout"), equalTo(10.seconds.toMillis))
        assertThat(settings.UnstartedPushTimeout.duration, equalTo(10.seconds))

        assertThat(settings.Loggers.size, equalTo(1))
        assertThat(settings.Loggers.head, equalTo(classOf[DefaultLogger].getName))
        assertThat(getStringList("akka.loggers").get(0), equalTo(classOf[DefaultLogger].getName))

        assertThat(getMilliseconds("akka.logger-startup-timeout"), equalTo(5.seconds.toMillis))
        assertThat(settings.LoggerStartTimeout.duration, equalTo(5.seconds))

        assertThat(getInt("akka.log-dead-letters"), equalTo(10))
        assertThat(settings.LogDeadLetters, equalTo(10))

        assertThat(getBoolean("akka.log-dead-letters-during-shutdown"), equalTo(true))
        assertThat(settings.LogDeadLettersDuringShutdown, equalTo(true))
      }

      {
        val c = config.getConfig("akka.actor.default-dispatcher")

        //General dispatcher config

        {
          assertThat(c.getString("type"), equalTo("Dispatcher"))
          c.getString("executor") must equal("fork-join-executor")
          assertThat(c.getMilliseconds("shutdown-timeout"), equalTo(1 * 1000))
          c.getInt("throughput") must equal(5)
          assertThat(c.getMilliseconds("throughput-deadline-time"), equalTo(0))
          c.getBoolean("attempt-teamwork") must equal(true)
        }

        //Fork join executor config

        {
          val pool = c.getConfig("fork-join-executor")
          assertThat(pool.getInt("parallelism-min"), equalTo(8))
          pool.getDouble("parallelism-factor") must equal(3.0)
          assertThat(pool.getInt("parallelism-max"), equalTo(64))
        }

        //Thread pool executor config

        {
          val pool = c.getConfig("thread-pool-executor")
          import pool._
          assertThat(getMilliseconds("keep-alive-time"), equalTo(60 * 1000))
          getDouble("core-pool-size-factor") must equal(3.0)
          assertThat(getDouble("max-pool-size-factor"), equalTo(3.0))
          getInt("task-queue-size") must equal(-1)
          assertThat(getString("task-queue-type"), equalTo("linked"))
          getBoolean("allow-core-timeout") must equal(true)
        }

        // Debug config
        {
          val debug = config.getConfig("akka.actor.debug")
          import debug._
          assertThat(getBoolean("receive"), equalTo(false))
          assertThat(settings.AddLoggingReceive, equalTo(false))

          assertThat(getBoolean("autoreceive"), equalTo(false))
          assertThat(settings.DebugAutoReceive, equalTo(false))

          assertThat(getBoolean("lifecycle"), equalTo(false))
          assertThat(settings.DebugLifecycle, equalTo(false))

          assertThat(getBoolean("fsm"), equalTo(false))
          assertThat(settings.FsmDebugEvent, equalTo(false))

          assertThat(getBoolean("event-stream"), equalTo(false))
          assertThat(settings.DebugEventStream, equalTo(false))

          assertThat(getBoolean("unhandled"), equalTo(false))
          assertThat(settings.DebugUnhandledMessage, equalTo(false))

          assertThat(getBoolean("router-misconfiguration"), equalTo(false))
          assertThat(settings.DebugRouterMisconfiguration, equalTo(false))
        }

        // IO config
        {
          val io = config.getConfig("akka.io")
          val ioExtSettings = IOManager(system).settings
          assertThat(ioExtSettings.readBufferSize, equalTo(8192))
          assertThat(io.getBytes("read-buffer-size"), equalTo(ioExtSettings.readBufferSize))

          assertThat(ioExtSettings.selectInterval, equalTo(100))
          assertThat(io.getInt("select-interval"), equalTo(ioExtSettings.selectInterval))

          assertThat(ioExtSettings.defaultBacklog, equalTo(1000))
          assertThat(io.getInt("default-backlog"), equalTo(ioExtSettings.defaultBacklog))
        }
      }

      {
        val c = config.getConfig("akka.actor.default-mailbox")

        // general mailbox config

        {
          assertThat(c.getInt("mailbox-capacity"), equalTo(1000))
          c.getMilliseconds("mailbox-push-timeout-time") must equal(10 * 1000)
          assertThat(c.getString("mailbox-type"), equalTo("akka.dispatch.UnboundedMailbox"))
        }
      }
    }
  }