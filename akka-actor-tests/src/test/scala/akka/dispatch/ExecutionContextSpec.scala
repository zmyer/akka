package akka.dispatch

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import java.util.concurrent.{ ExecutorService, Executor, Executors }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent._
import akka.testkit.{ TestLatch, AkkaSpec, DefaultTimeout }
import akka.util.SerializedSuspendableExecutionContext

class ExecutionContextSpec extends AkkaSpec with DefaultTimeout {

  
    @Test def `must be instantiable`: Unit = {
      val es = Executors.newCachedThreadPool()
      try {
        val executor: Executor with ExecutionContext = ExecutionContext.fromExecutor(es)
        assertThat(executor, notNullValue)

        val executorService: ExecutorService with ExecutionContext = ExecutionContext.fromExecutorService(es)
        assertThat(executorService, notNullValue)

        val jExecutor: ExecutionContextExecutor = ExecutionContext.fromExecutor(es)
        assertThat(jExecutor, notNullValue)

        val jExecutorService: ExecutionContextExecutorService = ExecutionContexts.fromExecutorService(es)
        assertThat(jExecutorService, notNullValue)
      } finally {
        es.shutdown
      }
    }

    @Test def `must be able to use Batching`: Unit = {
      assertThat(system.dispatcher.isInstanceOf[BatchingExecutor], equalTo(true))

      import system.dispatcher

      def batchable[T](f: ⇒ T)(implicit ec: ExecutionContext): Unit = ec.execute(new Batchable {
        override def isBatchable = true
        override def run: Unit = f
      })

      val p = Promise[Unit]()
      batchable {
        val lock, callingThreadLock, count = new AtomicInteger(0)
        callingThreadLock.compareAndSet(0, 1) // Enable the lock
        (1 to 100) foreach { i ⇒
          batchable {
            if (callingThreadLock.get != 0) p.tryFailure(new IllegalStateException("Batch was executed inline!"))
            else if (count.incrementAndGet == 100) p.trySuccess(()) //Done
            else if (lock.compareAndSet(0, 1)) {
              try Thread.sleep(10) finally lock.compareAndSet(1, 0)
            } else p.tryFailure(new IllegalStateException("Executed batch in parallel!"))
          }
        }
        callingThreadLock.compareAndSet(1, 0) // Disable the lock
      }
      assertThat(Await.result(p.future, timeout.duration), equalTo((())))
    }

    @Test def `must be able to avoid starvation when Batching is used and Await/blocking is called`: Unit = {
      assertThat(system.dispatcher.isInstanceOf[BatchingExecutor], equalTo(true))
      import system.dispatcher

      def batchable[T](f: ⇒ T)(implicit ec: ExecutionContext): Unit = ec.execute(new Batchable {
        override def isBatchable = true
        override def run: Unit = f
      })

      val latch = TestLatch(101)
      batchable {
        (1 to 100) foreach { i ⇒
          batchable {
            val deadlock = TestLatch(1)
            batchable { deadlock.open() }
            Await.ready(deadlock, timeout.duration)
            latch.countDown()
          }
        }
        latch.countDown()
      }
      Await.ready(latch, timeout.duration)
    }
  }

      @Test def `must be suspendable and resumable`: Unit = {
      val sec = SerializedSuspendableExecutionContext(1)(ExecutionContext.global)
      val counter = new AtomicInteger(0)
      def perform(f: Int ⇒ Int) = sec execute new Runnable { def run = counter.set(f(counter.get)) }
      perform(_ + 1)
      perform(x ⇒ { sec.suspend(); x * 2 })
      awaitCond(counter.get == 2)
      perform(_ + 4)
      perform(_ * 2)
      assertThat(sec.size, equalTo(2))
      Thread.sleep(500)
      assertThat(sec.size, equalTo(2))
      counter.get must be === 2
      sec.resume()
      awaitCond(counter.get == 12)
      perform(_ * 2)
      awaitCond(counter.get == 24)
      assertThat(sec.isEmpty, equalTo(true))
    }

    @Test def `must execute 'throughput' number of tasks per sweep`: Unit = {
      val submissions = new AtomicInteger(0)
      val counter = new AtomicInteger(0)
      val underlying = new ExecutionContext {
        override def execute(r: Runnable) { submissions.incrementAndGet(); ExecutionContext.global.execute(r) }
        override def reportFailure(t: Throwable) { ExecutionContext.global.reportFailure(t) }
      }
      val throughput = 25
      val sec = SerializedSuspendableExecutionContext(throughput)(underlying)
      sec.suspend()
      def perform(f: Int ⇒ Int) = sec execute new Runnable { def run = counter.set(f(counter.get)) }

      val total = 1000
      1 to total foreach { _ ⇒ perform(_ + 1) }
      assertThat(sec.size(), equalTo(total))
      sec.resume()
      awaitCond(counter.get == total)
      assertThat(submissions.get, equalTo((total / throughput)))
      sec.isEmpty must be === true
    }

    @Test def `must execute tasks in serial`: Unit = {
      val sec = SerializedSuspendableExecutionContext(1)(ExecutionContext.global)
      val total = 10000
      val counter = new AtomicInteger(0)
      def perform(f: Int ⇒ Int) = sec execute new Runnable { def run = counter.set(f(counter.get)) }

      1 to total foreach { i ⇒ perform(c ⇒ if (c == (i - 1)) c + 1 else c) }
      awaitCond(counter.get == total)
      assertThat(sec.isEmpty, equalTo(true))
    }

    @Test def `must relinquish thread when suspended`: Unit = {
      val submissions = new AtomicInteger(0)
      val counter = new AtomicInteger(0)
      val underlying = new ExecutionContext {
        override def execute(r: Runnable) { submissions.incrementAndGet(); ExecutionContext.global.execute(r) }
        override def reportFailure(t: Throwable) { ExecutionContext.global.reportFailure(t) }
      }
      val throughput = 25
      val sec = SerializedSuspendableExecutionContext(throughput)(underlying)
      sec.suspend()
      def perform(f: Int ⇒ Int) = sec execute new Runnable { def run = counter.set(f(counter.get)) }
      perform(_ + 1)
      1 to 10 foreach { _ ⇒ perform(identity) }
      perform(x ⇒ { sec.suspend(); x * 2 })
      perform(_ + 8)
      assertThat(sec.size, equalTo(13))
      sec.resume()
      awaitCond(counter.get == 2)
      sec.resume()
      awaitCond(counter.get == 10)
      assertThat(sec.isEmpty, equalTo(true))
      submissions.get must be === 2
    }
  }