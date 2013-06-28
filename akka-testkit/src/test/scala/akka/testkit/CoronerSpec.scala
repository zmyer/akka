/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import java.io._
import java.lang.management.ManagementFactory
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.duration._
import scala.concurrent.Await

class CoronerSpec {

  private def captureOutput[A](f: PrintStream ⇒ A): (A, String) = {
    val bytes = new ByteArrayOutputStream()
    val out = new PrintStream(bytes, true, "UTF-8")
    val result = f(out)
    (result, new String(bytes.toByteArray(), "UTF-8"))
  }

  
    @Test def `must generate a report if enough time passes`: Unit = {
      val (_, report) = captureOutput(out ⇒ {
        val coroner = Coroner.watch(100.milliseconds, "XXXX", out)
        Await.ready(coroner, 5.seconds)
        coroner.cancel()
      })
      report must include("Coroner's Report")
      report must include("XXXX")
    }

    @Test def `must not generate a report if cancelled early`: Unit = {
      val (_, report) = captureOutput(out ⇒ {
        val coroner = Coroner.watch(60.seconds, "XXXX", out)
        coroner.cancel()
        Await.ready(coroner, 1.seconds)
      })
      assertThat(report, equalTo(""))
    }

    @Test def `must display thread counts if enabled`: Unit = {
      val (_, report) = captureOutput(out ⇒ {
        val coroner = Coroner.watch(60.seconds, "XXXX", out, displayThreadCounts = true)
        coroner.cancel()
        Await.ready(coroner, 1.second)
      })
      report must include("Coroner Thread Count starts at ")
      report must include("Coroner Thread Count started at ")
      report must include("XXXX")
      report must not include ("Coroner's Report")
    }

    @Test def `must display deadlock information in its report`: Unit = {

      // Create two threads that each recursively synchronize on a list of
      // objects. Give each thread the same objects, but in reversed order.
      // Control execution of the threads so that they each hold an object
      // that the other wants to synchronize on. BOOM! Deadlock. Generate a
      // report, then clean up and check the report contents.

      case class LockingThread(name: String, thread: Thread, ready: Semaphore, proceed: Semaphore)

      def lockingThread(name: String, initialLocks: List[ReentrantLock]): LockingThread = {
        val ready = new Semaphore(0)
        val proceed = new Semaphore(0)
        val t = new Thread(new Runnable {
          def run = try recursiveLock(initialLocks) catch { case _: InterruptedException ⇒ () }

          def recursiveLock(locks: List[ReentrantLock]) {
            locks match {
              case Nil ⇒ ()
              case lock :: rest ⇒ {
                ready.release()
                proceed.acquire()
                lock.lockInterruptibly() // Allows us to break deadlock and free threads
                try {
                  recursiveLock(rest)
                } finally {
                  lock.unlock()
                }
              }
            }
          }
        }, name)
        t.start()
        LockingThread(name, t, ready, proceed)
      }

      val x = new ReentrantLock()
      val y = new ReentrantLock()
      val a = lockingThread("deadlock-thread-a", List(x, y))
      val b = lockingThread("deadlock-thread-b", List(y, x))

      // Walk threads into deadlock
      a.ready.acquire()
      b.ready.acquire()
      a.proceed.release()
      b.proceed.release()
      a.ready.acquire()
      b.ready.acquire()
      a.proceed.release()
      b.proceed.release()

      val (_, report) = captureOutput(Coroner.printReport("Deadlock test", _))

      a.thread.interrupt()
      b.thread.interrupt()

      report must include("Coroner's Report")

      // Split test based on JVM capabilities. Not all JVMs can detect
      // deadlock between ReentrantLocks. However, we need to use
      // ReentrantLocks because normal, monitor-based locks cannot be
      // un-deadlocked once this test is finished.

      val threadMx = ManagementFactory.getThreadMXBean()
      if (threadMx.isSynchronizerUsageSupported()) {
        val sectionHeading = "Deadlocks found for monitors and ownable synchronizers"
        report must include(sectionHeading)
        val deadlockSection = report.split(sectionHeading)(1)
        deadlockSection must include("deadlock-thread-a")
        deadlockSection must include("deadlock-thread-b")
      } else {
        val sectionHeading = "Deadlocks found for monitors, but NOT ownable synchronizers"
        report must include(sectionHeading)
        val deadlockSection = report.split(sectionHeading)(1)
        deadlockSection must include("None")
        deadlockSection must not include ("deadlock-thread-a")
        deadlockSection must not include ("deadlock-thread-b")
      }
    }

  }