/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.reflectiveCalls
import language.postfixOps

import akka.actor._
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.DeadLetter
import akka.pattern.ask

class AkkaSpecSpec {

  @Test def `must warn about unhandled messages`: Unit = {
    implicit val system = ActorSystem("AkkaSpec0", AkkaSpec.testConf)
    try {
      val a = system.actorOf(Props.empty)
      EventFilter.warning(start = "unhandled message", occurrences = 1) intercept {
        a ! 42
      }
    } finally {
      TestKit.shutdownActorSystem(system)
    }
  }

  @Test def `must terminate all actors`: Unit = {
    // verbose config just for demonstration purposes, please leave in in case of debugging
    import scala.collection.JavaConverters._
    val conf = Map(
      "akka.actor.debug.lifecycle" -> true, "akka.actor.debug.event-stream" -> true,
      "akka.loglevel" -> "DEBUG", "akka.stdout-loglevel" -> "DEBUG")
    val system = ActorSystem("AkkaSpec1", ConfigFactory.parseMap(conf.asJava).withFallback(AkkaSpec.testConf))
    val spec = new AkkaSpec(system) {
      val ref = Seq(testActor, system.actorOf(Props.empty, "name"))
    }
    spec.ref foreach (r ⇒ assertThat(r.isTerminated, not(true)))
    TestKit.shutdownActorSystem(system)
    spec.awaitCond(spec.ref forall (_.isTerminated), 2 seconds)
  }

  @Test def `must stop correctly when sending PoisonPill to rootGuardian`: Unit = {
    val system = ActorSystem("AkkaSpec2", AkkaSpec.testConf)
    val spec = new AkkaSpec(system) {}
    val latch = new TestLatch(1)(system)
    system.registerOnTermination(latch.countDown())

    system.actorSelection("/") ! PoisonPill

    Await.ready(latch, 2 seconds)
  }

  @Test def `must enqueue unread messages from testActor to deadLetters`: Unit = {
    val system, otherSystem = ActorSystem("AkkaSpec3", AkkaSpec.testConf)

    try {
      var locker = Seq.empty[DeadLetter]
      implicit val timeout = TestKitExtension(system).DefaultTimeout
      val davyJones = otherSystem.actorOf(Props(new Actor {
        def receive = {
          case m: DeadLetter ⇒ locker :+= m
          case "Die!"        ⇒ sender ! "finally gone"; context.stop(self)
        }
      }), "davyJones")

      system.eventStream.subscribe(davyJones, classOf[DeadLetter])

      val probe = new TestProbe(system)
      probe.ref.tell(42, davyJones)
      /*
       * this will ensure that the message is actually received, otherwise it
       * may happen that the system.stop() suspends the testActor before it had
       * a chance to put the message into its private queue
       */
      probe.receiveWhile(1 second) {
        case null ⇒
      }

      val latch = new TestLatch(1)(system)
      system.registerOnTermination(latch.countDown())
      TestKit.shutdownActorSystem(system)
      Await.ready(latch, 2 seconds)
      assertThat(Await.result(davyJones ? "Die!", timeout.duration), equalTo("finally gone"))

      // this will typically also contain log messages which were sent after the logger shutdown
      assertThat(locker.toArray, arrayContaining(DeadLetter(42, davyJones, probe.ref)))
    } finally {
      TestKit.shutdownActorSystem(system)
      TestKit.shutdownActorSystem(otherSystem)
    }
  }
}