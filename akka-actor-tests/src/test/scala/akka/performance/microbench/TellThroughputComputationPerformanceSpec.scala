package akka.performance.microbench

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import akka.performance.workbench.PerformanceSpec
import akka.actor._
import java.util.concurrent.{ ThreadPoolExecutor, CountDownLatch, TimeUnit }
import akka.dispatch._
import scala.concurrent.duration._

// -server -Xms512M -Xmx1024M -XX:+UseParallelGC -Dbenchmark=true -Dbenchmark.repeatFactor=500
class TellThroughputComputationPerformanceSpec extends PerformanceSpec {
  import TellThroughputComputationPerformanceSpec._

  val repeat = 500L * repeatFactor

      @Test def `must warmup`: Unit = {
      runScenario(4, warmup = true)
    }
    //    @Test def `must warmup more`: Unit = {
    //      runScenario(4, warmup = true)
    //    }
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
    @Test def `must perform with load 10`: Unit = {
      runScenario(10)
    }
    @Test def `must perform with load 12`: Unit = {
      runScenario(12)
    }
    @Test def `must perform with load 14`: Unit = {
      runScenario(14)
    }
    @Test def `must perform with load 16`: Unit = {
      runScenario(16)
    }
    @Test def `must perform with load 18`: Unit = {
      runScenario(18)
    }
    @Test def `must perform with load 20`: Unit = {
      runScenario(20)
    }
    @Test def `must perform with load 22`: Unit = {
      runScenario(22)
    }
    @Test def `must perform with load 24`: Unit = {
      runScenario(24)
    }
    @Test def `must perform with load 26`: Unit = {
      runScenario(26)
    }
    @Test def `must perform with load 28`: Unit = {
      runScenario(28)
    }
    @Test def `must perform with load 30`: Unit = {
      runScenario(30)
    }
    @Test def `must perform with load 32`: Unit = {
      runScenario(32)
    }
    @Test def `must perform with load 34`: Unit = {
      runScenario(34)
    }
    @Test def `must perform with load 36`: Unit = {
      runScenario(36)
    }
    @Test def `must perform with load 38`: Unit = {
      runScenario(38)
    }
    @Test def `must perform with load 40`: Unit = {
      runScenario(40)
    }
    @Test def `must perform with load 42`: Unit = {
      runScenario(42)
    }
    @Test def `must perform with load 44`: Unit = {
      runScenario(44)
    }
    @Test def `must perform with load 46`: Unit = {
      runScenario(46)
    }
    @Test def `must perform with load 48`: Unit = {
      runScenario(48)
    }

    def runScenario(numberOfClients: Int, warmup: Boolean = false) {
      if (acceptClients(numberOfClients)) {

        val throughputDispatcher = "benchmark.throughput-dispatcher"

        val latch = new CountDownLatch(numberOfClients)
        val repeatsPerClient = repeat / numberOfClients
        val destinations = for (i ← 0 until numberOfClients)
          yield system.actorOf(Props(new Destination).withDispatcher(throughputDispatcher))
        val clients = for (dest ← destinations)
          yield system.actorOf(Props(new Client(dest, latch, repeatsPerClient)).withDispatcher(throughputDispatcher))

        val start = System.nanoTime
        clients.foreach(_ ! Run)
        val ok = latch.await(maxRunDuration.toMillis, TimeUnit.MILLISECONDS)
        val durationNs = (System.nanoTime - start)

        if (!warmup) {
          assertThat(ok, equalTo(true))
          logMeasurement(numberOfClients, durationNs, repeat)
        }
        clients.foreach(system.stop(_))
        destinations.foreach(system.stop(_))

      }
    }
  }
}

object TellThroughputComputationPerformanceSpec {

  case object Run
  case object Msg

  trait PiComputation {
    private var _pi: Double = 0.0
    def pi: Double = _pi
    private var currentPosition = 0L
    def nrOfElements = 1000

    def calculatePi(): Unit = {
      _pi += calculateDecimals(currentPosition)
      currentPosition += nrOfElements
    }

    private def calculateDecimals(start: Long): Double = {
      var acc = 0.0
      for (i ← start until (start + nrOfElements))
        acc += 4.0 * (1 - (i % 2) * 2) / (2 * i + 1)
      acc
    }

  }

  class Destination extends Actor with PiComputation {
    def receive = {
      case Msg ⇒
        calculatePi()
        sender ! Msg
    }
  }

  class Client(
    actor: ActorRef,
    latch: CountDownLatch,
    repeat: Long) extends Actor with PiComputation {

    var sent = 0L
    var received = 0L

    def receive = {
      case Msg ⇒
        received += 1
        calculatePi()
        if (sent < repeat) {
          actor ! Msg
          sent += 1
        } else if (received >= repeat) {
          //println("PI: " + pi)
          latch.countDown()
        }
      case Run ⇒
        for (i ← 0L until math.min(1000L, repeat)) {
          actor ! Msg
          sent += 1
        }
    }

  }