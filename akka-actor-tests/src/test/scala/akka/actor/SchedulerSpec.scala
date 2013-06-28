/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.junit.Test
import org.junit.experimental.categories.Category
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps
import java.io.Closeable
import java.util.concurrent._
import atomic.{ AtomicReference, AtomicInteger }
import scala.concurrent.{ future, Await, ExecutionContext }
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.Try
import scala.util.control.NonFatal
import com.typesafe.config.{ Config, ConfigFactory }
import akka.pattern.ask
import akka.testkit._

object SchedulerSpec {
  val testConf = ConfigFactory.parseString("""
    akka.scheduler.implementation = akka.actor.DefaultScheduler
    akka.scheduler.ticks-per-wheel = 32
  """).withFallback(AkkaSpec.testConf)

  val testConfRevolver = ConfigFactory.parseString("""
    akka.scheduler.implementation = akka.actor.LightArrayRevolverScheduler
  """).withFallback(testConf)
}

trait SchedulerSpec extends BeforeAndAfterEach with DefaultTimeout with ImplicitSender { this: AkkaSpec ⇒
  import system.dispatcher

  def collectCancellable(c: Cancellable): Cancellable


    @Test @Category(Array(classOf[TimingTest])) def `must schedule more than once`: Unit = {
      case object Tick
      case object Tock

      val tickActor, tickActor2 = system.actorOf(Props(new Actor {
        var ticks = 0
        def receive = {
          case Tick ⇒
            if (ticks < 3) {
              sender ! Tock
              ticks += 1
            }
        }
      }))
      // run every 50 milliseconds
      collectCancellable(system.scheduler.schedule(0 milliseconds, 50 milliseconds, tickActor, Tick))

      // after max 1 second it should be executed at least the 3 times already
      expectMsg(Tock)
      expectMsg(Tock)
      expectMsg(Tock)
      expectNoMsg(500 millis)

      collectCancellable(system.scheduler.schedule(0 milliseconds, 50 milliseconds)(tickActor2 ! Tick))

      // after max 1 second it should be executed at least the 3 times already
      expectMsg(Tock)
      expectMsg(Tock)
      expectMsg(Tock)
      expectNoMsg(500 millis)
    }

    @Test @Category(Array(classOf[TimingTest])) def `must stop continuous scheduling if the receiving actor has been terminated`: Unit = {
      val actor = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender ! x } }))

      // run immediately and then every 100 milliseconds
      collectCancellable(system.scheduler.schedule(0 milliseconds, 100 milliseconds, actor, "msg"))
      expectMsg("msg")

      // stop the actor and, hence, the continuous messaging from happening
      system stop actor

      expectNoMsg(500 millis)
    }

    @Test @Category(Array(classOf[TimingTest])) def `must schedule once`: Unit = {
      case object Tick
      val countDownLatch = new CountDownLatch(3)
      val tickActor = system.actorOf(Props(new Actor {
        def receive = { case Tick ⇒ countDownLatch.countDown() }
      }))

      // run after 300 millisec
      collectCancellable(system.scheduler.scheduleOnce(300 milliseconds, tickActor, Tick))
      collectCancellable(system.scheduler.scheduleOnce(300 milliseconds)(countDownLatch.countDown()))

      // should not be run immediately
      assert(countDownLatch.await(100, TimeUnit.MILLISECONDS) == false)
      assertThat(countDownLatch.getCount, equalTo(3))

      // after 1 second the wait should fail
      assert(countDownLatch.await(2, TimeUnit.SECONDS) == false)
      // should still be 1 left
      assertThat(countDownLatch.getCount, equalTo(1))
    }

    /**
     * ticket #372
     */
    @Test @Category(Array(classOf[TimingTest])) def `must be cancellable`: Unit = {
      for (_ ← 1 to 10) system.scheduler.scheduleOnce(1 second, testActor, "fail").cancel()

      expectNoMsg(2 seconds)
    }

    @Test @Category(Array(classOf[TimingTest])) def `must be cancellable during initial delay`: Unit = {
      val ticks = new AtomicInteger

      val initialDelay = 200.milliseconds.dilated
      val delay = 10.milliseconds.dilated
      val timeout = collectCancellable(system.scheduler.schedule(initialDelay, delay) {
        ticks.incrementAndGet()
      })
      Thread.sleep(10.milliseconds.dilated.toMillis)
      timeout.cancel()
      Thread.sleep((initialDelay + 100.milliseconds.dilated).toMillis)

      assertThat(ticks.get, equalTo(0))
    }

    @Test @Category(Array(classOf[TimingTest])) def `must be cancellable after initial delay`: Unit = {
      val ticks = new AtomicInteger

      val initialDelay = 90.milliseconds.dilated
      val delay = 500.milliseconds.dilated
      val timeout = collectCancellable(system.scheduler.schedule(initialDelay, delay) {
        ticks.incrementAndGet()
      })
      Thread.sleep((initialDelay + 200.milliseconds.dilated).toMillis)
      timeout.cancel()
      Thread.sleep((delay + 100.milliseconds.dilated).toMillis)

      assertThat(ticks.get, equalTo(1))
    }

    @Test def `must be canceled if cancel is performed before execution`: Unit = {
      val task = collectCancellable(system.scheduler.scheduleOnce(10 seconds)(()))
      assertThat(task.cancel(), equalTo(true))
      assertThat(task.isCancelled, equalTo(true))
      assertThat(task.cancel(), equalTo(false))
      assertThat(task.isCancelled, equalTo(true))
    }

    @Test def `must not be canceled if cancel is performed after execution`: Unit = {
      val latch = TestLatch(1)
      val task = collectCancellable(system.scheduler.scheduleOnce(10 millis)(latch.countDown()))
      Await.ready(latch, remaining)
      assertThat(task.cancel(), equalTo(false))
      assertThat(task.isCancelled, equalTo(false))
      assertThat(task.cancel(), equalTo(false))
      assertThat(task.isCancelled, equalTo(false))
    }

    /**
     * ticket #307
     */
    @Test @Category(Array(classOf[TimingTest])) def `must pick up schedule after actor restart`: Unit = {

      object Ping
      object Crash

      val restartLatch = new TestLatch
      val pingLatch = new TestLatch(6)

      val supervisor = system.actorOf(Props(new Supervisor(AllForOneStrategy(3, 1 second)(List(classOf[Exception])))))
      val props = Props(new Actor {
        def receive = {
          case Ping  ⇒ pingLatch.countDown()
          case Crash ⇒ throw new Exception("CRASH")
        }

        override def postRestart(reason: Throwable) = restartLatch.open
      })
      val actor = Await.result((supervisor ? props).mapTo[ActorRef], timeout.duration)

      collectCancellable(system.scheduler.schedule(500 milliseconds, 500 milliseconds, actor, Ping))
      // appx 2 pings before crash
      EventFilter[Exception]("CRASH", occurrences = 1) intercept {
        collectCancellable(system.scheduler.scheduleOnce(1000 milliseconds, actor, Crash))
      }

      Await.ready(restartLatch, 2 seconds)
      // should be enough time for the ping countdown to recover and reach 6 pings
      Await.ready(pingLatch, 5 seconds)
    }

    @Test @Category(Array(classOf[TimingTest])) def `must never fire prematurely`: Unit = {
      val ticks = new TestLatch(300)

      case class Msg(ts: Long)

      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case Msg(ts) ⇒
            val now = System.nanoTime
            // Make sure that no message has been dispatched before the scheduled time (10ms) has occurred
            if (now < ts) throw new RuntimeException("Interval is too small: " + (now - ts))
            ticks.countDown()
        }
      }))

      (1 to 300).foreach { i ⇒
        collectCancellable(system.scheduler.scheduleOnce(20 milliseconds, actor, Msg(System.nanoTime)))
        Thread.sleep(5)
      }

      Await.ready(ticks, 3 seconds)
    }

    @Test @Category(Array(classOf[TimingTest])) def `must schedule with different initial delay and frequency`: Unit = {
      val ticks = new TestLatch(3)

      case object Msg

      val actor = system.actorOf(Props(new Actor {
        def receive = { case Msg ⇒ ticks.countDown() }
      }))

      val startTime = System.nanoTime()
      collectCancellable(system.scheduler.schedule(1 second, 300 milliseconds, actor, Msg))
      Await.ready(ticks, 3 seconds)

      // LARS is a bit more aggressive in scheduling recurring tasks at the right
      // frequency and may execute them a little earlier; the actual expected timing
      // is 1599ms on a fast machine or 1699ms on a loaded one (plus some room for jenkins)
      assertThat((System.nanoTime() - startTime).nanos.toMillis, equalTo(1750L plusOrMinus 250))
    }

    @Test @Category(Array(classOf[TimingTest])) def `must adjust for scheduler inaccuracy`: Unit = {
      val startTime = System.nanoTime
      val n = 200
      val latch = new TestLatch(n)
      system.scheduler.schedule(25.millis, 25.millis) { latch.countDown() }
      Await.ready(latch, 6.seconds)
      // Rate
      assertThat(n * 1000.0 / (System.nanoTime - startTime).nanos.toMillis, equalTo(40.0 plusOrMinus 4))
    }

    @Test @Category(Array(classOf[TimingTest])) def `must not be affected by long running task`: Unit = {
      val startTime = System.nanoTime
      val n = 22
      val latch = new TestLatch(n)
      system.scheduler.schedule(225.millis, 225.millis) {
        Thread.sleep(80)
        latch.countDown()
      }
      Await.ready(latch, 6.seconds)
      // Rate
      assertThat(n * 1000.0 / (System.nanoTime - startTime).nanos.toMillis, equalTo(4.4 plusOrMinus 0.3))
    }

    @Test @Category(Array(classOf[TimingTest])) def `must handle timeouts equal to multiple of wheel period`: Unit = {
      val timeout = 3200 milliseconds
      val barrier = TestLatch()
      import system.dispatcher
      val job = system.scheduler.scheduleOnce(timeout)(barrier.countDown())
      try {
        Await.ready(barrier, 5000 milliseconds)
      } finally {
        job.cancel()
      }
    }

    @Test @Category(Array(classOf[TimingTest])) def `must survive being stressed without cancellation`: Unit = {
      val r = ThreadLocalRandom.current()
      val N = 100000
      for (_ ← 1 to N) {
        val next = r.nextInt(3000)
        val now = System.nanoTime
        system.scheduler.scheduleOnce(next.millis) {
          val stop = System.nanoTime
          testActor ! (stop - now - next * 1000000L)
        }
      }
      val latencies = within(10.seconds) {
        for (i ← 1 to N) yield try expectMsgType[Long] catch {
          case NonFatal(e) ⇒ throw new Exception(s"failed expecting the $i-th latency", e)
        }
      }
      val histogram = latencies groupBy (_ / 100000000L)
      for (k ← histogram.keys.toSeq.sorted) {
        system.log.info(f"${k * 100}%3d: ${histogram(k).size}")
      }
    }
  }
}

class DefaultSchedulerSpec extends AkkaSpec(SchedulerSpec.testConf) with SchedulerSpec {
  private val cancellables = new ConcurrentLinkedQueue[Cancellable]()

  def collectCancellable(c: Cancellable): Cancellable = {
    cancellables.add(c)
    c
  }

  override def afterEach {
    while (cancellables.peek() ne null) {
      for (c ← Option(cancellables.poll())) {
        c.cancel()
      }
    }
  }

}

class LightArrayRevolverSchedulerSpec extends AkkaSpec(SchedulerSpec.testConfRevolver) with SchedulerSpec {

  def collectCancellable(c: Cancellable): Cancellable = c


    @Test @Category(Array(classOf[TimingTest])) def `must survive being stressed with cancellation`: Unit = {
      import system.dispatcher
      val r = ThreadLocalRandom.current
      val N = 1000000
      val tasks = for (_ ← 1 to N) yield {
        val next = r.nextInt(3000)
        val now = System.nanoTime
        system.scheduler.scheduleOnce(next.millis) {
          val stop = System.nanoTime
          testActor ! (stop - now - next * 1000000L)
        }
      }
      // get somewhat into the middle of things
      Thread.sleep(500)
      val cancellations = for (t ← tasks) yield {
        t.cancel()
        if (t.isCancelled) 1 else 0
      }
      val cancelled = cancellations.sum
      println(cancelled)
      val latencies = within(10.seconds) {
        for (i ← 1 to (N - cancelled)) yield try expectMsgType[Long] catch {
          case NonFatal(e) ⇒ throw new Exception(s"failed expecting the $i-th latency", e)
        }
      }
      val histogram = latencies groupBy (_ / 100000000L)
      for (k ← histogram.keys.toSeq.sorted) {
        system.log.info(f"${k * 100}%3d: ${histogram(k).size}")
      }
      expectNoMsg(1.second)
    }

    @Test def `must survive vicious enqueueing`: Unit = {
      withScheduler(config = ConfigFactory.parseString("akka.scheduler.ticks-per-wheel=2")) { (sched, driver) ⇒
        import driver._
        import system.dispatcher
        val counter = new AtomicInteger
        val terminated = future {
          var rounds = 0
          while (Try(sched.scheduleOnce(Duration.Zero)(())(localEC)).isSuccess) {
            Thread.sleep(1)
            driver.wakeUp(step)
            rounds += 1
          }
          rounds
        }
        def delay = if (ThreadLocalRandom.current.nextBoolean) step * 2 else step
        val N = 1000000
        (1 to N) foreach (_ ⇒ sched.scheduleOnce(delay)(counter.incrementAndGet()))
        sched.close()
        Await.result(terminated, 3.seconds.dilated) must be > 10
        awaitCond(counter.get == N)
      }
    }

    @Test def `must execute multiple jobs at once when expiring multiple buckets`: Unit = {
      withScheduler() { (sched, driver) ⇒
        implicit def ec = localEC
        import driver._
        val start = step / 2
        (0 to 3) foreach (i ⇒ sched.scheduleOnce(start + step * i, testActor, "hello"))
        expectNoMsg(step)
        wakeUp(step)
        expectWait(step)
        wakeUp(step * 4 + step / 2)
        expectWait(step / 2)
        (0 to 3) foreach (_ ⇒ expectMsg(Duration.Zero, "hello"))
      }
    }

    @Test def `must properly defer jobs even when the timer thread oversleeps`: Unit = {
      withScheduler() { (sched, driver) ⇒
        implicit def ec = localEC
        import driver._
        sched.scheduleOnce(step * 3, probe.ref, "hello")
        wakeUp(step * 5)
        expectWait(step)
        wakeUp(step * 2)
        expectWait(step)
        wakeUp(step)
        probe.expectMsg("hello")
        expectWait(step)
      }
    }

    @Test def `must correctly wrap around wheel rounds`: Unit = {
      withScheduler(config = ConfigFactory.parseString("akka.scheduler.ticks-per-wheel=2")) { (sched, driver) ⇒
        implicit def ec = localEC
        import driver._
        val start = step / 2
        (0 to 3) foreach (i ⇒ sched.scheduleOnce(start + step * i, probe.ref, "hello"))
        probe.expectNoMsg(step)
        wakeUp(step)
        expectWait(step)
        // the following are no for-comp to see which iteration fails
        wakeUp(step)
        probe.expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        probe.expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        probe.expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        probe.expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        expectWait(step)
      }
    }

    @Test def `must correctly execute jobs when clock wraps around`: Unit = {
      withScheduler(Long.MaxValue - 200000000L) { (sched, driver) ⇒
        implicit def ec = localEC
        import driver._
        val start = step / 2
        (0 to 3) foreach (i ⇒ sched.scheduleOnce(start + step * i, testActor, "hello"))
        expectNoMsg(step)
        wakeUp(step)
        expectWait(step)
        // the following are no for-comp to see which iteration fails
        wakeUp(step)
        expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        expectWait(step)
      }
    }

    @Test def `must reliably reject jobs when shutting down`: Unit = {
      withScheduler() { (sched, driver) ⇒
        import system.dispatcher
        val counter = new AtomicInteger
        future { Thread.sleep(5); driver.close(); sched.close() }
        val headroom = 200
        var overrun = headroom
        val cap = 1000000
        val (success, failure) = Iterator
          .continually(Try(sched.scheduleOnce(100.millis)(counter.incrementAndGet())))
          .take(cap)
          .takeWhile(_.isSuccess || { overrun -= 1; overrun >= 0 })
          .partition(_.isSuccess)
        val s = success.size
        s must be < cap
        awaitCond(s == counter.get, message = s"$s was not ${counter.get}")
        assertThat(failure.size, equalTo(headroom))
      }
    }
  }

  trait Driver {
    def wakeUp(d: FiniteDuration): Unit
    def expectWait(): FiniteDuration
    def expectWait(d: FiniteDuration) { expectWait() must be(d) }
    def probe: TestProbe
    def step: FiniteDuration
    def close(): Unit
  }

  val localEC = new ExecutionContext {
    def execute(runnable: Runnable) { runnable.run() }
    def reportFailure(t: Throwable) { t.printStackTrace() }
  }

  def withScheduler(start: Long = 0L, config: Config = ConfigFactory.empty)(thunk: (Scheduler with Closeable, Driver) ⇒ Unit): Unit = {
    import akka.actor.{ LightArrayRevolverScheduler ⇒ LARS }
    val lbq = new AtomicReference[LinkedBlockingQueue[Long]](new LinkedBlockingQueue[Long])
    val prb = TestProbe()
    val tf = system.asInstanceOf[ActorSystemImpl].threadFactory
    val sched =
      new { @volatile var time = start } with LARS(config.withFallback(system.settings.config), log, tf) {
        override protected def clock(): Long = {
          // println(s"clock=$time")
          time
        }
        override protected def getShutdownTimeout: FiniteDuration = super.getShutdownTimeout.dilated

        override protected def waitNanos(ns: Long): Unit = {
          // println(s"waiting $ns")
          prb.ref ! ns
          try time += (lbq.get match {
            case q: LinkedBlockingQueue[Long] ⇒ q.take()
            case _                            ⇒ 0L
          })
          catch {
            case _: InterruptedException ⇒ Thread.currentThread.interrupt()
          }
        }
      }
    val driver = new Driver {
      def wakeUp(d: FiniteDuration) = lbq.get match {
        case q: LinkedBlockingQueue[Long] ⇒ q.offer(d.toNanos)
        case _                            ⇒
      }
      def expectWait(): FiniteDuration = probe.expectMsgType[Long].nanos
      def probe = prb
      def step = sched.TickDuration
      def close() = lbq.getAndSet(null) match {
        case q: LinkedBlockingQueue[Long] ⇒ q.offer(0L)
        case _                            ⇒
      }
    }
    driver.expectWait()
    try thunk(sched, driver)
    catch {
      case NonFatal(ex) ⇒
        try {
          driver.close()
          sched.close()
        } catch { case _: Exception ⇒ }
        throw ex
    }
    driver.close()
    sched.close()
  }