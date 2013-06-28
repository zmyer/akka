package akka.performance.trading.system

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Random
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics
import org.apache.commons.math.stat.descriptive.SynchronizedDescriptiveStatistics
import org.junit.runner.RunWith
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.performance.trading.domain.Ask
import akka.performance.trading.domain.Bid
import akka.performance.trading.domain.Order
import akka.performance.trading.domain.TotalTradeCounter
import akka.performance.workbench.PerformanceSpec
import akka.performance.trading.domain.Orderbook
import akka.performance.trading.domain.TotalTradeCounterExtension

// -server -Xms512M -Xmx1024M -XX:+UseParallelGC -Dbenchmark=true -Dbenchmark.repeatFactor=500 -Dbenchmark.useDummyOrderbook=true
class TradingThroughputPerformanceSpec extends PerformanceSpec {

  var tradingSystem: AkkaTradingSystem = _

  def totalTradeCounter: TotalTradeCounter = TotalTradeCounterExtension(system)

  override def beforeEach() {
    super.beforeEach()
    tradingSystem = new AkkaTradingSystem(system)
    tradingSystem.start()
    totalTradeCounter.reset()
  }

  override def afterEach() {
    super.afterEach()
    tradingSystem.shutdown()
  }

  getClass.getSimpleName must {
    @Test def `must warmup`: Unit = {
      runScenario(4, warmup = true)
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
    @Test def `must perform with load 10`: Unit = {
      runScenario(10)
    }

  }

  def runScenario(numberOfClients: Int, warmup: Boolean = false) {
    if (acceptClients(numberOfClients)) {

      val repeat = 400L * repeatFactor

      val prefixes = "A" :: "B" :: "C" :: "D" :: "E" :: "F" :: Nil
      val askOrders = for {
        s ← prefixes
        i ← 1 to 4
      } yield Ask(s + i, 100 - i, 1000)
      val bidOrders = for {
        s ← prefixes
        i ← 1 to 4
      } yield Bid(s + i, 100 - i, 1000)
      val orders = askOrders.zip(bidOrders).map(x ⇒ Seq(x._1, x._2)).flatten

      val throughputDispatcher = "benchmark.trading-dispatcher"

      val ordersPerClient = repeat * orders.size / numberOfClients
      val totalNumberOfOrders = ordersPerClient * numberOfClients
      val latch = new CountDownLatch(numberOfClients)
      val receivers = tradingSystem.orderReceivers.toIndexedSeq
      val start = System.nanoTime
      val clients = (for (i ← 0 until numberOfClients) yield {
        val receiver = receivers(i % receivers.size)
        val props = Props(new Client(receiver, orders, latch, ordersPerClient)).withDispatcher(throughputDispatcher)
        system.actorOf(props)
      })

      clients.foreach(_ ! "run")
      val ok = latch.await(maxRunDuration.toMillis, TimeUnit.MILLISECONDS)
      val durationNs = (System.nanoTime - start)

      if (!warmup) {
        assertThat(ok, equalTo(true))
        if (!Orderbook.useDummyOrderbook) {
          assertThat(totalTradeCounter.count, equalTo(totalNumberOfOrders / 2))
        }
        logMeasurement(numberOfClients, durationNs, totalNumberOfOrders)
      }
      clients.foreach(system.stop(_))
    }
  }

  class Client(
    orderReceiver: ActorRef,
    orders: List[Order],
    latch: CountDownLatch,
    repeat: Long) extends Actor {

    var orderIterator = orders.toIterator
    def nextOrder(): Order = {
      if (!orderIterator.hasNext) {
        orderIterator = orders.toIterator
      }
      orderIterator.next()
    }

    var sent = 0L
    var received = 0L

    def receive = {
      case Rsp(order, status) ⇒
        if (!status) {
          log.error("Invalid rsp")
        }
        received += 1
        if (sent < repeat) {
          placeOrder()
          sent += 1
        } else if (received >= repeat) {
          latch.countDown()
        }

      case "run" ⇒
        for (i ← 0L until math.min(1000L, repeat)) {
          placeOrder()
          sent += 1
        }
    }

    def placeOrder() {
      orderReceiver ! nextOrder()
    }

  }

}

