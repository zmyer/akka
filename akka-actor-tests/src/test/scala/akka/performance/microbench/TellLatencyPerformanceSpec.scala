package akka.performance.microbench

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import akka.performance.workbench.PerformanceSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics
import org.junit.runner.RunWith
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import java.util.Random
import org.apache.commons.math.stat.descriptive.SynchronizedDescriptiveStatistics

// -server -Xms512M -Xmx1024M -XX:+UseConcMarkSweepGC -Dbenchmark=true -Dbenchmark.repeatFactor=500
class TellLatencyPerformanceSpec extends PerformanceSpec {
  import TellLatencyPerformanceSpec._

  val repeat = 200L * repeatFactor

  var stat: DescriptiveStatistics = _

  override def beforeEach() {
    stat = new SynchronizedDescriptiveStatistics
  }

      @Test def `must warmup`: Unit = {
      runScenario(2, warmup = true)
    }
    @Test def `must warmup more`: Unit = {
      runScenario(4, warmup = true)
    }
    @Test def `must perform with load 1`: Unit = {
      runScenario(1)
    }
    @Test def `must perform with load 2`: Unit = {
      runScenario(2)
    }
    @Test def `must perform with load 4`: Unit = {
      runScenario(4)
    }
    @Test def `must perform with load 6`: Unit = {
      runScenario(6)
    }
    @Test def `must perform with load 8`: Unit = {
      runScenario(8)
    }

    def runScenario(numberOfClients: Int, warmup: Boolean = false) {
      if (acceptClients(numberOfClients)) {

        val dispatcherKey = "benchmark.latency-dispatcher"
        val latch = new CountDownLatch(numberOfClients)
        val repeatsPerClient = repeat / numberOfClients
        val clients = (for (i ← 0 until numberOfClients) yield {
          val destination = system.actorOf(Props[Destination].withDispatcher(dispatcherKey))
          val w4 = system.actorOf(Props(new Waypoint(destination)).withDispatcher(dispatcherKey))
          val w3 = system.actorOf(Props(new Waypoint(w4)).withDispatcher(dispatcherKey))
          val w2 = system.actorOf(Props(new Waypoint(w3)).withDispatcher(dispatcherKey))
          val w1 = system.actorOf(Props(new Waypoint(w2)).withDispatcher(dispatcherKey))
          Props(new Client(w1, latch, repeatsPerClient, clientDelay.toMicros.intValue, stat)).withDispatcher(dispatcherKey)
        }).toList.map(system.actorOf(_))

        val start = System.nanoTime
        clients.foreach(_ ! Run)
        val ok = latch.await(maxRunDuration.toMillis, TimeUnit.MILLISECONDS)
        val durationNs = (System.nanoTime - start)

        if (!warmup) {
          assertThat(ok, equalTo(true))
          logMeasurement(numberOfClients, durationNs, stat)
        }
        clients.foreach(system.stop(_))

      }
    }
  }
}

object TellLatencyPerformanceSpec {

  val random: Random = new Random(0)

  case object Run
  case class Msg(nanoTime: Long = System.nanoTime)

  class Waypoint(next: ActorRef) extends Actor {
    def receive = {
      case msg: Msg ⇒ next forward msg
    }
  }

  class Destination extends Actor {
    def receive = {
      case msg: Msg ⇒ sender ! msg
    }
  }

  class Client(
    actor: ActorRef,
    latch: CountDownLatch,
    repeat: Long,
    delayMicros: Int,
    stat: DescriptiveStatistics) extends Actor {

    var sent = 0L
    var received = 0L

    def receive = {
      case Msg(sendTime) ⇒
        val duration = System.nanoTime - sendTime
        stat.addValue(duration)
        received += 1
        if (sent < repeat) {
          PerformanceSpec.shortDelay(delayMicros, received)
          actor ! Msg()
          sent += 1
        } else if (received >= repeat) {
          latch.countDown()
        }
      case Run ⇒
        // random initial delay to spread requests
        val initialDelay = random.nextInt(20)
        Thread.sleep(initialDelay)
        actor ! Msg()
        sent += 1
    }

  }