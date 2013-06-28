/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorRef, ActorSystemImpl, ActorSystem, Props, UnhandledMessage }
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._
import akka.event.Logging.InitializeLogger
import akka.pattern.gracefulStop
import akka.testkit.{ TestProbe, AkkaSpec }

object EventStreamSpec {

  val config = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = WARNING
        loglevel = INFO
        loggers = ["akka.event.EventStreamSpec$MyLog", "%s"]
      }
      """.format(Logging.StandardOutLogger.getClass.getName))

  val configUnhandled = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = WARNING
        loglevel = DEBUG
        actor.debug.unhandled = on
      }
      """)

  case class M(i: Int)

  case class SetTarget(ref: ActorRef)

  class MyLog extends Actor {
    var dst: ActorRef = context.system.deadLetters
    def receive = {
      case Logging.InitializeLogger(bus) ⇒
        bus.subscribe(context.self, classOf[SetTarget])
        bus.subscribe(context.self, classOf[UnhandledMessage])
        sender ! Logging.LoggerInitialized
      case SetTarget(ref)      ⇒ dst = ref; dst ! "OK"
      case e: Logging.LogEvent ⇒ dst ! e
      case u: UnhandledMessage ⇒ dst ! u
    }
  }

  // class hierarchy for subchannel test
  class A
  class B1 extends A
  class B2 extends A
  class C extends B1

  trait T
  trait AT extends T
  trait ATT extends AT
  trait BT extends T
  trait BTT extends BT
  class CC
  class CCATBT extends CC with ATT with BTT
}

class EventStreamSpec extends AkkaSpec(EventStreamSpec.config) {

  import EventStreamSpec._

  val impl = system.asInstanceOf[ActorSystemImpl]

  
    @Test def `must manage subscriptions`: Unit = {
      val bus = new EventStream(true)
      bus.subscribe(testActor, classOf[M])
      bus.publish(M(42))
      within(1 second) {
        expectMsg(M(42))
        bus.unsubscribe(testActor)
        bus.publish(M(13))
        expectNoMsg
      }
    }

    @Test def `must not allow null as subscriber`: Unit = {
      val bus = new EventStream(true)
      assertThat(intercept[IllegalArgumentException] { bus.subscribe(null, classOf[M]) }.getMessage, equalTo("subscriber is null"))
    }

    @Test def `must not allow null as unsubscriber`: Unit = {
      val bus = new EventStream(true)
      assertThat(intercept[IllegalArgumentException] { bus.unsubscribe(null, classOf[M]) }.getMessage, equalTo("subscriber is null"))
      assertThat(intercept[IllegalArgumentException] { bus.unsubscribe(null) }.getMessage, equalTo("subscriber is null"))
    }

    @Test def `must be able to log unhandled messages`: Unit = {
      val sys = ActorSystem("EventStreamSpecUnhandled", configUnhandled)
      try {
        sys.eventStream.subscribe(testActor, classOf[AnyRef])
        val m = UnhandledMessage(42, sys.deadLetters, sys.deadLetters)
        sys.eventStream.publish(m)
        expectMsgAllOf(m, Logging.Debug(sys.deadLetters.path.toString, sys.deadLetters.getClass, "unhandled message from " + sys.deadLetters + ": 42"))
        sys.eventStream.unsubscribe(testActor)
      } finally {
        shutdown(sys)
      }
    }

    @Test def `must manage log levels`: Unit = {
      val bus = new EventStream(false)
      bus.startDefaultLoggers(impl)
      bus.publish(SetTarget(testActor))
      expectMsg("OK")
      within(2 seconds) {
        import Logging._
        verifyLevel(bus, InfoLevel)
        bus.setLogLevel(WarningLevel)
        verifyLevel(bus, WarningLevel)
        bus.setLogLevel(DebugLevel)
        verifyLevel(bus, DebugLevel)
        bus.setLogLevel(ErrorLevel)
        verifyLevel(bus, ErrorLevel)
      }
    }

    @Test def `must manage sub-channels using classes`: Unit = {
      val a = new A
      val b1 = new B1
      val b2 = new B2
      val c = new C
      val bus = new EventStream(false)
      within(2 seconds) {
        assertThat(bus.subscribe(testActor, classOf[B2]), equalTo(true))
        bus.publish(c)
        bus.publish(b2)
        expectMsg(b2)
        assertThat(bus.subscribe(testActor, classOf[A]), equalTo(true))
        bus.publish(c)
        expectMsg(c)
        bus.publish(b1)
        expectMsg(b1)
        assertThat(bus.unsubscribe(testActor, classOf[B1]), equalTo(true))
        bus.publish(c)
        bus.publish(b2)
        bus.publish(a)
        expectMsg(b2)
        expectMsg(a)
        expectNoMsg
      }
    }

    @Test def `must manage sub-channels using classes and traits (update on subscribe)`: Unit = {
      val es = new EventStream(false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2, a3, a4 = TestProbe()

      assertThat(es.subscribe(a1.ref, classOf[AT]), equalTo(true))
      es.subscribe(a2.ref, classOf[BT]) must be === true
      assertThat(es.subscribe(a3.ref, classOf[CC]), equalTo(true))
      es.subscribe(a4.ref, classOf[CCATBT]) must be === true
      es.publish(tm1)
      es.publish(tm2)
      assertThat(a1.expectMsgType[AT], equalTo(tm2))
      a2.expectMsgType[BT] must be === tm2
      assertThat(a3.expectMsgType[CC], equalTo(tm1))
      a3.expectMsgType[CC] must be === tm2
      assertThat(a4.expectMsgType[CCATBT], equalTo(tm2))
      es.unsubscribe(a1.ref, classOf[AT]) must be === true
      assertThat(es.unsubscribe(a2.ref, classOf[BT]), equalTo(true))
      es.unsubscribe(a3.ref, classOf[CC]) must be === true
      assertThat(es.unsubscribe(a4.ref, classOf[CCATBT]), equalTo(true))
    }

    @Test def `must manage sub-channels using classes and traits (update on unsubscribe)`: Unit = {
      val es = new EventStream(false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2, a3, a4 = TestProbe()

      assertThat(es.subscribe(a1.ref, classOf[AT]), equalTo(true))
      es.subscribe(a2.ref, classOf[BT]) must be === true
      assertThat(es.subscribe(a3.ref, classOf[CC]), equalTo(true))
      es.subscribe(a4.ref, classOf[CCATBT]) must be === true
      assertThat(es.unsubscribe(a3.ref, classOf[CC]), equalTo(true))
      es.publish(tm1)
      es.publish(tm2)
      assertThat(a1.expectMsgType[AT], equalTo(tm2))
      a2.expectMsgType[BT] must be === tm2
      a3.expectNoMsg(1 second)
      assertThat(a4.expectMsgType[CCATBT], equalTo(tm2))
      es.unsubscribe(a1.ref, classOf[AT]) must be === true
      assertThat(es.unsubscribe(a2.ref, classOf[BT]), equalTo(true))
      es.unsubscribe(a4.ref, classOf[CCATBT]) must be === true
    }

    @Test def `must manage sub-channels using classes and traits (update on unsubscribe all)`: Unit = {
      val es = new EventStream(false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2, a3, a4 = TestProbe()

      assertThat(es.subscribe(a1.ref, classOf[AT]), equalTo(true))
      es.subscribe(a2.ref, classOf[BT]) must be === true
      assertThat(es.subscribe(a3.ref, classOf[CC]), equalTo(true))
      es.subscribe(a4.ref, classOf[CCATBT]) must be === true
      es.unsubscribe(a3.ref)
      es.publish(tm1)
      es.publish(tm2)
      assertThat(a1.expectMsgType[AT], equalTo(tm2))
      a2.expectMsgType[BT] must be === tm2
      a3.expectNoMsg(1 second)
      assertThat(a4.expectMsgType[CCATBT], equalTo(tm2))
      es.unsubscribe(a1.ref, classOf[AT]) must be === true
      assertThat(es.unsubscribe(a2.ref, classOf[BT]), equalTo(true))
      es.unsubscribe(a4.ref, classOf[CCATBT]) must be === true
    }

    @Test def `must manage sub-channels using classes and traits (update on publish)`: Unit = {
      val es = new EventStream(false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2 = TestProbe()

      assertThat(es.subscribe(a1.ref, classOf[AT]), equalTo(true))
      es.subscribe(a2.ref, classOf[BT]) must be === true
      es.publish(tm1)
      es.publish(tm2)
      assertThat(a1.expectMsgType[AT], equalTo(tm2))
      a2.expectMsgType[BT] must be === tm2
      assertThat(es.unsubscribe(a1.ref, classOf[AT]), equalTo(true))
      es.unsubscribe(a2.ref, classOf[BT]) must be === true
    }

    @Test def `must manage sub-channels using classes and traits (unsubscribe classes used with trait)`: Unit = {
      val es = new EventStream(false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2, a3 = TestProbe()

      assertThat(es.subscribe(a1.ref, classOf[AT]), equalTo(true))
      es.subscribe(a2.ref, classOf[BT]) must be === true
      assertThat(es.subscribe(a2.ref, classOf[CC]), equalTo(true))
      es.subscribe(a3.ref, classOf[CC]) must be === true
      assertThat(es.unsubscribe(a2.ref, classOf[CC]), equalTo(true))
      es.unsubscribe(a3.ref, classOf[CCATBT]) must be === true
      es.publish(tm1)
      es.publish(tm2)
      assertThat(a1.expectMsgType[AT], equalTo(tm2))
      a2.expectMsgType[BT] must be === tm2
      assertThat(a3.expectMsgType[CC], equalTo(tm1))
      es.unsubscribe(a1.ref, classOf[AT]) must be === true
      assertThat(es.unsubscribe(a2.ref, classOf[BT]), equalTo(true))
      es.unsubscribe(a3.ref, classOf[CC]) must be === true
    }

    @Test def `must manage sub-channels using classes and traits (subscribe after publish)`: Unit = {
      val es = new EventStream(false)
      val tm1 = new CCATBT
      val a1, a2 = TestProbe()

      assertThat(es.subscribe(a1.ref, classOf[AT]), equalTo(true))
      es.publish(tm1)
      assertThat(a1.expectMsgType[AT], equalTo(tm1))
      a2.expectNoMsg(1 second)
      assertThat(es.subscribe(a2.ref, classOf[BTT]), equalTo(true))
      es.publish(tm1)
      assertThat(a1.expectMsgType[AT], equalTo(tm1))
      a2.expectMsgType[BTT] must be === tm1
      assertThat(es.unsubscribe(a1.ref, classOf[AT]), equalTo(true))
      es.unsubscribe(a2.ref, classOf[BTT]) must be === true
    }
  }

  private def verifyLevel(bus: LoggingBus, level: Logging.LogLevel) {
    import Logging._
    val allmsg = Seq(Debug("", null, "debug"), Info("", null, "info"), Warning("", null, "warning"), Error("", null, "error"))
    val msg = allmsg filter (_.level <= level)
    allmsg foreach bus.publish
    msg foreach (expectMsg(_))
  }