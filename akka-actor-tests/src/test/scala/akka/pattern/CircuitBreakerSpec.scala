/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.pattern

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import scala.concurrent.duration._
import akka.testkit._
import akka.actor.{ ActorSystem, Scheduler }
import scala.concurrent.{ ExecutionContext, Future, Await }

object CircuitBreakerSpec {

  class TestException extends RuntimeException

  class Breaker(val instance: CircuitBreaker)(implicit system: ActorSystem) {
    val halfOpenLatch = new TestLatch(1)
    val openLatch = new TestLatch(1)
    val closedLatch = new TestLatch(1)
    def apply(): CircuitBreaker = instance
    instance.onClose(closedLatch.countDown()).onHalfOpen(halfOpenLatch.countDown()).onOpen(openLatch.countDown())
  }

  def shortCallTimeoutCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, 50.millis.dilated, 500.millis.dilated))

  def shortResetTimeoutCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, 1000.millis.dilated, 50.millis.dilated))

  def longCallTimeoutCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, 5 seconds, 500.millis.dilated))

  def longResetTimeoutCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, 100.millis.dilated, 5 seconds))

  def multiFailureCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 5, 200.millis.dilated, 500.millis.dilated))
}

class CircuitBreakerSpec extends AkkaSpec with BeforeAndAfter {
  import CircuitBreakerSpec.TestException
  implicit def ec = system.dispatcher
  implicit def s = system

  val awaitTimeout = 2.seconds.dilated

  def checkLatch(latch: TestLatch): Unit = Await.ready(latch, awaitTimeout)

  def throwException = throw new TestException

  def sayHi = "hi"

      @Test def `must throw exceptions when called before reset timeout`: Unit = {
      val breaker = CircuitBreakerSpec.longResetTimeoutCb()

      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }

      checkLatch(breaker.openLatch)

      intercept[CircuitBreakerOpenException] { breaker().withSyncCircuitBreaker(sayHi) }
    }

    @Test def `must transition to half-open on reset timeout`: Unit = {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)
    }
  }

      @Test def `must pass through next call and close on success`: Unit = {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)
      assert("hi" == breaker().withSyncCircuitBreaker(sayHi))
      checkLatch(breaker.closedLatch)
    }

    @Test def `must open on exception in call`: Unit = {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.openLatch)
    }
  }

      @Test def `must allow calls through`: Unit = {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      assertThat(breaker().withSyncCircuitBreaker(sayHi), equalTo("hi"))
    }

    @Test def `must increment failure count on failure`: Unit = {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      assertThat(breaker().currentFailureCount, equalTo(0))
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.openLatch)
      assertThat(breaker().currentFailureCount, equalTo(1))
    }

    @Test def `must reset failure count after success`: Unit = {
      val breaker = CircuitBreakerSpec.multiFailureCb()
      assertThat(breaker().currentFailureCount, equalTo(0))
      intercept[TestException] {
        val ct = Thread.currentThread() // Ensure that the thunk is executed in the tests thread
        breaker().withSyncCircuitBreaker({ if (Thread.currentThread() eq ct) throwException else "fail" })
      }
      assertThat(breaker().currentFailureCount, equalTo(1))
      breaker().withSyncCircuitBreaker(sayHi)
      assertThat(breaker().currentFailureCount, equalTo(0))
    }

    @Test def `must increment failure count on callTimeout`: Unit = {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      breaker().withSyncCircuitBreaker(Thread.sleep(100.millis.dilated.toMillis))
      awaitCond(breaker().currentFailureCount == 1, remaining)
    }
  }

      @Test def `must throw exceptions when called before reset timeout`: Unit = {
      val breaker = CircuitBreakerSpec.longResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))

      checkLatch(breaker.openLatch)

      intercept[CircuitBreakerOpenException] { Await.result(breaker().withCircuitBreaker(Future(sayHi)), awaitTimeout) }
    }

    @Test def `must transition to half-open on reset timeout`: Unit = {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)
    }
  }

      @Test def `must pass through next call and close on success`: Unit = {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)
      assertThat(Await.result(breaker().withCircuitBreaker(Future(sayHi)), awaitTimeout), equalTo("hi"))
      checkLatch(breaker.closedLatch)
    }

    @Test def `must re-open on exception in call`: Unit = {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)
      intercept[TestException] { Await.result(breaker().withCircuitBreaker(Future(throwException)), awaitTimeout) }
      checkLatch(breaker.openLatch)
    }

    @Test def `must re-open on async failure`: Unit = {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)

      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.openLatch)
    }
  }

      @Test def `must allow calls through`: Unit = {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      assertThat(Await.result(breaker().withCircuitBreaker(Future(sayHi)), awaitTimeout), equalTo("hi"))
    }

    @Test def `must increment failure count on exception`: Unit = {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      intercept[TestException] { Await.result(breaker().withCircuitBreaker(Future(throwException)), awaitTimeout) }
      checkLatch(breaker.openLatch)
      assertThat(breaker().currentFailureCount, equalTo(1))
    }

    @Test def `must increment failure count on async failure`: Unit = {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.openLatch)
      assertThat(breaker().currentFailureCount, equalTo(1))
    }

    @Test def `must reset failure count after success`: Unit = {
      val breaker = CircuitBreakerSpec.multiFailureCb()
      breaker().withCircuitBreaker(Future(sayHi))
      for (n ← 1 to 4) breaker().withCircuitBreaker(Future(throwException))
      awaitCond(breaker().currentFailureCount == 4, awaitTimeout)
      breaker().withCircuitBreaker(Future(sayHi))
      awaitCond(breaker().currentFailureCount == 0, awaitTimeout)
    }

    @Test def `must increment failure count on callTimeout`: Unit = {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      breaker().withCircuitBreaker(Future { Thread.sleep(100.millis.dilated.toMillis); sayHi })
      checkLatch(breaker.openLatch)
      assertThat(breaker().currentFailureCount, equalTo(1))
    }
  }