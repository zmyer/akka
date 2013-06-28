/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps
import akka.testkit._
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import scala.concurrent.duration._
import java.util.concurrent.{ RejectedExecutionException, ConcurrentLinkedQueue }
import akka.util.Timeout
import akka.japi.Util.immutableSeq
import scala.concurrent.Future
import akka.pattern.ask
import akka.dispatch._
import com.typesafe.config.Config
import java.util.concurrent.{ LinkedBlockingQueue, BlockingQueue, TimeUnit }
import akka.util.Switch

object TestExtension extends ExtensionId[TestExtension] with ExtensionIdProvider {
  def lookup = this
  def createExtension(s: ExtendedActorSystem) = new TestExtension(s)
}

// Dont't place inside ActorSystemSpec object, since it will not be garbage collected and reference to system remains
class TestExtension(val system: ExtendedActorSystem) extends Extension

object ActorSystemSpec {

  class Waves extends Actor {
    var master: ActorRef = _
    var terminaters = Set[ActorRef]()

    def receive = {
      case n: Int ⇒
        master = sender
        terminaters = Set() ++ (for (i ← 1 to n) yield {
          val man = context.watch(context.system.actorOf(Props[Terminater]))
          man ! "run"
          man
        })
      case Terminated(child) if terminaters contains child ⇒
        terminaters -= child
        if (terminaters.isEmpty) {
          master ! "done"
          context stop self
        }
    }

    override def preRestart(cause: Throwable, msg: Option[Any]) {
      if (master ne null) {
        master ! "failed with " + cause + " while processing " + msg
      }
      context stop self
    }
  }

  class Terminater extends Actor {
    def receive = {
      case "run" ⇒ context.stop(self)
    }
  }

  class Strategy extends SupervisorStrategyConfigurator {
    def create() = OneForOneStrategy() {
      case _ ⇒ SupervisorStrategy.Escalate
    }
  }

  case class FastActor(latch: TestLatch, testActor: ActorRef) extends Actor {
    val ref1 = context.actorOf(Props.empty)
    val ref2 = context.actorFor(ref1.path.toString)
    testActor ! ref2.getClass
    latch.countDown()

    def receive = {
      case _ ⇒
    }
  }

  class SlowDispatcher(_config: Config, _prerequisites: DispatcherPrerequisites) extends MessageDispatcherConfigurator(_config, _prerequisites) {
    private val instance = new Dispatcher(
      this,
      config.getString("id"),
      config.getInt("throughput"),
      Duration(config.getNanoseconds("throughput-deadline-time"), TimeUnit.NANOSECONDS),
      configureExecutor(),
      Duration(config.getMilliseconds("shutdown-timeout"), TimeUnit.MILLISECONDS)) {
      val doneIt = new Switch
      override protected[akka] def registerForExecution(mbox: Mailbox, hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = {
        val ret = super.registerForExecution(mbox, hasMessageHint, hasSystemMessageHint)
        doneIt.switchOn {
          TestKit.awaitCond(mbox.actor.actor != null, 1.second)
          mbox.actor.actor match {
            case FastActor(latch, _) ⇒ Await.ready(latch, 1.second)
          }
        }
        ret
      }
    }

    /**
     * Returns the same dispatcher instance for each invocation
     */
    override def dispatcher(): MessageDispatcher = instance
  }

  val config = s"""
      akka.extensions = ["akka.actor.TestExtension"]
      slow {
        type="${classOf[SlowDispatcher].getName}"
      }"""

}

class ActorSystemSpec extends AkkaSpec(ActorSystemSpec.config) with ImplicitSender {

  import ActorSystemSpec.FastActor

  def `use scala.concurrent.Future's InternalCallbackEC`: Unit = {
    assertThat(system.asInstanceOf[ActorSystemImpl].internalCallingThreadExecutionContext.getClass.getName, equalTo("scala.concurrent.Future$InternalCallbackExecutor$"))
  }

  @Test def `must reject invalid names`: Unit = {
    for (
      n ← Seq(
        "hallo_welt",
        "-hallowelt",
        "hallo*welt",
        "hallo@welt",
        "hallo#welt",
        "hallo$welt",
        "hallo%welt",
        "hallo/welt")
    ) intercept[IllegalArgumentException] {
      ActorSystem(n)
    }
  }

  @Test def `must allow valid names`: Unit = {
    shutdown(ActorSystem("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-"))
  }

  @Test def `must support extensions`: Unit = {
    // TestExtension is configured and should be loaded at startup
    assertThat(system.hasExtension(TestExtension), equalTo(true))
    assertThat(TestExtension(system).system, equalTo(system))
    assertThat(system.extension(TestExtension).system, equalTo(system))
  }

  @Test def `must log dead letters`: Unit = {
    val sys = ActorSystem("LogDeadLetters", ConfigFactory.parseString("akka.loglevel=INFO").withFallback(AkkaSpec.testConf))
    try {
      val a = sys.actorOf(Props[ActorSystemSpec.Terminater])
      EventFilter.info(pattern = "not delivered", occurrences = 1).intercept {
        a ! "run"
        a ! "boom"
      }(sys)
    } finally shutdown(sys)
  }

  @Test def `must run termination callbacks in order`: Unit = {
    val system2 = ActorSystem("TerminationCallbacks", AkkaSpec.testConf)
    val result = new ConcurrentLinkedQueue[Int]
    val count = 10
    val latch = TestLatch(count)

    for (i ← 1 to count) {
      system2.registerOnTermination {
        Thread.sleep((i % 3).millis.dilated.toMillis)
        result add i
        latch.countDown()
      }
    }

    system2.shutdown()
    Await.ready(latch, 5 seconds)

    val expected = (for (i ← 1 to count) yield i).reverse

    assertThat(immutableSeq(result), equalTo(expected))
  }

  @Test def `must awaitTermination after termination callbacks`: Unit = {
    val system2 = ActorSystem("AwaitTermination", AkkaSpec.testConf)
    @volatile
    var callbackWasRun = false

    system2.registerOnTermination {
      Thread.sleep(50.millis.dilated.toMillis)
      callbackWasRun = true
    }
    import system.dispatcher
    system2.scheduler.scheduleOnce(200.millis.dilated) { system2.shutdown() }

    system2.awaitTermination(5 seconds)
    assertThat(callbackWasRun, equalTo(true))
  }

  @Test def `must return isTerminated status correctly`: Unit = {
    val system = ActorSystem()
    assertThat(system.isTerminated, equalTo(false))
    system.shutdown()
    system.awaitTermination(10 seconds)
    assertThat(system.isTerminated, equalTo(true))
  }

  @Test def `must throw RejectedExecutionException when shutdown`: Unit = {
    val system2 = ActorSystem("AwaitTermination", AkkaSpec.testConf)
    system2.shutdown()
    system2.awaitTermination(10 seconds)

    assertThat(intercept[RejectedExecutionException] {
      system2.registerOnTermination { println("IF YOU SEE THIS THEN THERE'S A BUG HERE") }
    }.getMessage, equalTo("Must be called prior to system shutdown."))
  }

  @Test def `must reliably create waves of actors`: Unit = {
    import system.dispatcher
    implicit val timeout = Timeout((20 seconds).dilated)
    val waves = for (i ← 1 to 3) yield system.actorOf(Props[ActorSystemSpec.Waves]) ? 50000
    assertThat(Await.result(Future.sequence(waves), timeout.duration + 5.seconds), equalTo(Seq("done", "done", "done")))
  }

  @Test def `must find actors that just have been created`: Unit = {
    system.actorOf(Props(new FastActor(TestLatch(), testActor)).withDispatcher("slow"))
    assertThat(expectMsgType[Class[_]], equalTo(classOf[LocalActorRef]))
  }

  @Test def `must reliable deny creation of actors while shutting down`: Unit = {
    val system = ActorSystem()
    import system.dispatcher
    system.scheduler.scheduleOnce(200 millis) { system.shutdown() }
    var failing = false
    var created = Vector.empty[ActorRef]
    while (!system.isTerminated) {
      try {
        val t = system.actorOf(Props[ActorSystemSpec.Terminater])
        failing must not be true // because once failing => always failing (it’s due to shutdown)
        created :+= t
      } catch {
        case _: IllegalStateException ⇒ failing = true
      }

      if (!failing && system.uptime >= 5) {
        println(created.last)
        println(system.asInstanceOf[ExtendedActorSystem].printTree)
        fail("System didn't terminate within 5 seconds")
      }
    }

    assertThat(created filter (ref ⇒ !ref.isTerminated && !ref.asInstanceOf[ActorRefWithCell].underlying.isInstanceOf[UnstartedCell]), equalTo(Seq()))
  }

  @Test def `must shut down when /user fails`: Unit = {
    implicit val system = ActorSystem("Stop", AkkaSpec.testConf)
    EventFilter[ActorKilledException]() intercept {
      system.actorSelection("/user") ! Kill
      awaitCond(system.isTerminated)
    }
  }

  @Test def `must allow configuration of guardian supervisor strategy`: Unit = {
    implicit val system = ActorSystem("Stop",
      ConfigFactory.parseString("akka.actor.guardian-supervisor-strategy=akka.actor.StoppingSupervisorStrategy")
        .withFallback(AkkaSpec.testConf))
    val a = system.actorOf(Props(new Actor {
      def receive = {
        case "die" ⇒ throw new Exception("hello")
      }
    }))
    val probe = TestProbe()
    probe.watch(a)
    EventFilter[Exception]("hello", occurrences = 1) intercept {
      a ! "die"
    }
    val t = probe.expectMsg(Terminated(a)(existenceConfirmed = true, addressTerminated = false))
    assertThat(t.existenceConfirmed, equalTo(true))
    assertThat(t.addressTerminated, equalTo(false))
    shutdown(system)
  }

  @Test def `must shut down when /user escalates`: Unit = {
    implicit val system = ActorSystem("Stop",
      ConfigFactory.parseString("akka.actor.guardian-supervisor-strategy=\"akka.actor.ActorSystemSpec$Strategy\"")
        .withFallback(AkkaSpec.testConf))
    val a = system.actorOf(Props(new Actor {
      def receive = {
        case "die" ⇒ throw new Exception("hello")
      }
    }))
    EventFilter[Exception]("hello") intercept {
      a ! "die"
      awaitCond(system.isTerminated)
    }
  }

}