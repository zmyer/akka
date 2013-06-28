package akka.dispatch

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import org.scalacheck._
import org.scalacheck.Arbitrary._
import org.scalacheck.Prop._
import org.scalacheck.Gen._
import akka.actor._
import akka.testkit.{ EventFilter, filterEvents, filterException, AkkaSpec, DefaultTimeout, TestLatch }
import scala.concurrent.{ Await, Awaitable, Future, Promise, ExecutionContext }
import scala.util.control.NonFatal
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.runtime.NonLocalReturnControl
import akka.pattern.ask
import java.lang.{ IllegalStateException, ArithmeticException }
import java.util.concurrent._
import scala.reflect.{ ClassTag, classTag }
import scala.util.{ Failure, Success, Try }

object FutureSpec {

  def ready[T](awaitable: Awaitable[T], atMost: Duration): awaitable.type =
    try Await.ready(awaitable, atMost) catch {
      case t: TimeoutException ⇒ throw t
      case e if NonFatal(e)    ⇒ awaitable //swallow
    }

  class TestActor extends Actor {
    def receive = {
      case "Hello" ⇒ sender ! "World"
      case "Failure" ⇒
        sender ! Status.Failure(new RuntimeException("Expected exception; to test fault-tolerance"))
      case "NoReply" ⇒
    }
  }

  class TestDelayActor(await: TestLatch) extends Actor {
    def receive = {
      case "Hello" ⇒
        FutureSpec.ready(await, TestLatch.DefaultTimeout); sender ! "World"
      case "NoReply" ⇒ FutureSpec.ready(await, TestLatch.DefaultTimeout)
      case "Failure" ⇒
        FutureSpec.ready(await, TestLatch.DefaultTimeout)
        sender ! Status.Failure(new RuntimeException("Expected exception; to test fault-tolerance"))
    }
  }
}

class JavaFutureSpec extends JavaFutureTests with JUnitSuite

class FutureSpec extends AkkaSpec with Checkers with DefaultTimeout {
  import FutureSpec._
  implicit val ec: ExecutionContext = system.dispatcher
  "A Promise" when {
          behave like emptyFuture(_(Promise().future))
      @Test def `must return supplied value on timeout`: Unit = {
        val failure = Promise.failed[String](new RuntimeException("br0ken")).future
        val otherFailure = Promise.failed[String](new RuntimeException("last")).future
        val empty = Promise[String]().future
        val timedOut = Promise.successful[String]("Timedout").future

        assertThat(Await.result(failure fallbackTo timedOut, timeout.duration), equalTo("Timedout"))
        assertThat(Await.result(timedOut fallbackTo empty, timeout.duration), equalTo("Timedout"))
        assertThat(Await.result(failure fallbackTo failure fallbackTo timedOut, timeout.duration), equalTo("Timedout"))
        intercept[RuntimeException] {
          Await.result(failure fallbackTo otherFailure, timeout.duration)
        assertThat(}.getMessage, equalTo("last"))
      }
    }
          val result = "test value"
      val future = Promise[String]().complete(Success(result)).future
      behave like futureWithResult(_(future, result))
    }
          val message = "Expected Exception"
      val future = Promise[String]().complete(Failure(new RuntimeException(message))).future
      behave like futureWithException[RuntimeException](_(future, message))
    }
          val message = "Boxed InterruptedException"
      val future = Promise[String]().complete(Failure(new InterruptedException(message))).future
      behave like futureWithException[RuntimeException](_(future, message))
    }
          val result = "test value"
      val future = Promise[String]().complete(Failure(new NonLocalReturnControl[String]("test", result))).future
      behave like futureWithResult(_(future, result))
    }

    @Test def `must have different ECs`: Unit = {
      def namedCtx(n: String) =
        ExecutionContext.fromExecutorService(
          Executors.newSingleThreadExecutor(new ThreadFactory { def newThread(r: Runnable) = new Thread(r, n) }))

      val A = namedCtx("A")
      val B = namedCtx("B")

      // create a promise with ctx A
      val p = Promise[String]()

      // I would expect that any callback from p
      // is executed in the context of p
      val result = {
        implicit val ec = A
        p.future map { _ + Thread.currentThread().getName() }
      }

      p.completeWith(Future { "Hi " }(B))
      try {
        assertThat(Await.result(result, timeout.duration), equalTo("Hi A"))
      } finally {
        A.shutdown()
        B.shutdown()
      }
    }
  }

  "A Future" when {

    "awaiting a result" which {
              behave like emptyFuture { test ⇒
          val latch = new TestLatch
          val result = "test value"
          val future = Future {
            FutureSpec.ready(latch, TestLatch.DefaultTimeout)
            result
          }
          test(future)
          latch.open()
          FutureSpec.ready(future, timeout.duration)
        }
      }
              behave like futureWithResult { test ⇒
          val latch = new TestLatch
          val result = "test value"
          val future = Future {
            FutureSpec.ready(latch, TestLatch.DefaultTimeout)
            result
          }
          latch.open()
          FutureSpec.ready(future, timeout.duration)
          test(future, result)
        }
      }
              @Test def `must pass checks`: Unit = {
          filterException[ArithmeticException] {
            check({ (future: Future[Int], actions: List[FutureAction]) ⇒
              def wrap[T](f: Future[T]): Try[T] = FutureSpec.ready(f, timeout.duration).value.get
              val result = (future /: actions)(_ /: _)
              val expected = (wrap(future) /: actions)(_ /: _)
              ((wrap(result), expected) match {
                case (Success(a), Success(b)) ⇒ a == b
                case (Failure(a), Failure(b)) if a.toString == b.toString ⇒ true
                case (Failure(a), Failure(b)) if a.getStackTrace.isEmpty || b.getStackTrace.isEmpty ⇒ a.getClass.toString == b.getClass.toString
                case _ ⇒ false
              }) :| result.value.get.toString + " is expected to be " + expected.toString
            }, minSuccessful(10000), workers(4))
          }
        }
      }
    }

    "from an Actor" which {
              behave like futureWithResult { test ⇒
          val actor = system.actorOf(Props[TestActor])
          val future = actor ? "Hello"
          FutureSpec.ready(future, timeout.duration)
          test(future, "World")
          system.stop(actor)
        }
      }
              behave like futureWithException[RuntimeException] { test ⇒
          filterException[RuntimeException] {
            val actor = system.actorOf(Props[TestActor])
            val future = actor ? "Failure"
            FutureSpec.ready(future, timeout.duration)
            test(future, "Expected exception; to test fault-tolerance")
            system.stop(actor)
          }
        }
      }
    }

    "using flatMap with an Actor" which {
              behave like futureWithResult { test ⇒
          val actor1 = system.actorOf(Props[TestActor])
          val actor2 = system.actorOf(Props(new Actor { def receive = { case s: String ⇒ sender ! s.toUpperCase } }))
          val future = actor1 ? "Hello" flatMap { case s: String ⇒ actor2 ? s }
          FutureSpec.ready(future, timeout.duration)
          test(future, "WORLD")
          system.stop(actor1)
          system.stop(actor2)
        }
      }
              behave like futureWithException[ArithmeticException] { test ⇒
          filterException[ArithmeticException] {
            val actor1 = system.actorOf(Props[TestActor])
            val actor2 = system.actorOf(Props(new Actor { def receive = { case s: String ⇒ sender ! Status.Failure(new ArithmeticException("/ by zero")) } }))
            val future = actor1 ? "Hello" flatMap { case s: String ⇒ actor2 ? s }
            FutureSpec.ready(future, timeout.duration)
            test(future, "/ by zero")
            system.stop(actor1)
            system.stop(actor2)
          }
        }
      }
              behave like futureWithException[NoSuchElementException] { test ⇒
          filterException[NoSuchElementException] {
            val actor1 = system.actorOf(Props[TestActor])
            val actor2 = system.actorOf(Props(new Actor { def receive = { case s: String ⇒ sender ! s.toUpperCase } }))
            val future = actor1 ? "Hello" flatMap { case i: Int ⇒ actor2 ? i }
            FutureSpec.ready(future, timeout.duration)
            test(future, "World (of class java.lang.String)")
            system.stop(actor1)
            system.stop(actor2)
          }
        }
      }
    }


      @Test def `must compose with for-comprehensions`: Unit = {
        filterException[ClassCastException] {
          val actor = system.actorOf(Props(new Actor {
            def receive = {
              case s: String ⇒ sender ! s.length
              case i: Int    ⇒ sender ! (i * 2).toString
            }
          }))

          val future0 = actor ? "Hello"

          val future1 = for {
            a ← future0.mapTo[Int] // returns 5
            b ← (actor ? a).mapTo[String] // returns "10"
            c ← (actor ? 7).mapTo[String] // returns "14"
          } yield b + "-" + c

          val future2 = for {
            a ← future0.mapTo[Int]
            b ← (actor ? a).mapTo[Int]
            c ← (actor ? 7).mapTo[String]
          } yield b + "-" + c

          assertThat(Await.result(future1, timeout.duration), equalTo("10-14"))
          assert(checkType(future1, classTag[String]))
          intercept[ClassCastException] { Await.result(future2, timeout.duration) }
          system.stop(actor)
        }
      }

      @Test def `must support pattern matching within a for-comprehension`: Unit = {
        filterException[NoSuchElementException] {
          case class Req[T](req: T)
          case class Res[T](res: T)
          val actor = system.actorOf(Props(new Actor {
            def receive = {
              case Req(s: String) ⇒ sender ! Res(s.length)
              case Req(i: Int)    ⇒ sender ! Res((i * 2).toString)
            }
          }))

          val future1 = for {
            Res(a: Int) ← actor ? Req("Hello")
            Res(b: String) ← actor ? Req(a)
            Res(c: String) ← actor ? Req(7)
          } yield b + "-" + c

          val future2 = for {
            Res(a: Int) ← actor ? Req("Hello")
            Res(b: Int) ← actor ? Req(a)
            Res(c: Int) ← actor ? Req(7)
          } yield b + "-" + c

          assertThat(Await.result(future1, timeout.duration), equalTo("10-14"))
          intercept[NoSuchElementException] { Await.result(future2, timeout.duration) }
          system.stop(actor)
        }
      }

      @Test def `must recover from exceptions`: Unit = {
        filterException[RuntimeException] {
          val future1 = Future(5)
          val future2 = future1 map (_ / 0)
          val future3 = future2 map (_.toString)

          val future4 = future1 recover {
            case e: ArithmeticException ⇒ 0
          } map (_.toString)

          val future5 = future2 recover {
            case e: ArithmeticException ⇒ 0
          } map (_.toString)

          val future6 = future2 recover {
            case e: MatchError ⇒ 0
          } map (_.toString)

          val future7 = future3 recover { case e: ArithmeticException ⇒ "You got ERROR" }

          val actor = system.actorOf(Props[TestActor])

          val future8 = actor ? "Failure"
          val future9 = actor ? "Failure" recover {
            case e: RuntimeException ⇒ "FAIL!"
          }
          val future10 = actor ? "Hello" recover {
            case e: RuntimeException ⇒ "FAIL!"
          }
          val future11 = actor ? "Failure" recover { case _ ⇒ "Oops!" }

          assertThat(Await.result(future1, timeout.duration), equalTo(5))
          intercept[ArithmeticException] { Await.result(future2, timeout.duration) }
          intercept[ArithmeticException] { Await.result(future3, timeout.duration) }
          assertThat(Await.result(future4, timeout.duration), equalTo("5"))
          assertThat(Await.result(future5, timeout.duration), equalTo("0"))
          intercept[ArithmeticException] { Await.result(future6, timeout.duration) }
          assertThat(Await.result(future7, timeout.duration), equalTo("You got ERROR"))
          intercept[RuntimeException] { Await.result(future8, timeout.duration) }
          assertThat(Await.result(future9, timeout.duration), equalTo("FAIL!"))
          assertThat(Await.result(future10, timeout.duration), equalTo("World"))
          assertThat(Await.result(future11, timeout.duration), equalTo("Oops!"))

          system.stop(actor)
        }
      }

      @Test def `must recoverWith from exceptions`: Unit = {
        val o = new IllegalStateException("original")
        val r = new IllegalStateException("recovered")
        val yay = Promise.successful("yay!").future

        intercept[IllegalStateException] {
          Await.result(Promise.failed[String](o).future recoverWith { case _ if false == true ⇒ yay }, timeout.duration)
        assertThat(}, equalTo(o))

        assertThat(Await.result(Promise.failed[String](o).future recoverWith { case _ ⇒ yay }, timeout.duration), equalTo("yay!"))

        intercept[IllegalStateException] {
          Await.result(Promise.failed[String](o).future recoverWith { case _ ⇒ Promise.failed[String](r).future }, timeout.duration)
        assertThat(}, equalTo(r))
      }

      @Test def `must andThen like a boss`: Unit = {
        val q = new LinkedBlockingQueue[Int]
        for (i ← 1 to 1000) {
          assertThat(Await.result(Future { q.add(1); 3 } andThen { case _ ⇒ q.add(2) } andThen { case Success(0) ⇒ q.add(Int.MaxValue) } andThen { case _ ⇒ q.add(3); }, timeout.duration), equalTo(3))
          assertThat(q.poll(), equalTo(1))
          assertThat(q.poll(), equalTo(2))
          assertThat(q.poll(), equalTo(3))
          q.clear()
        }
      }

      @Test def `must firstCompletedOf`: Unit = {
        val futures = Vector.fill[Future[Int]](10)(Promise[Int]().future) :+ Promise.successful[Int](5).future
        assertThat(Await.result(Future.firstCompletedOf(futures), timeout.duration), equalTo(5))
      }

      @Test def `must find`: Unit = {
        val futures = for (i ← 1 to 10) yield Future { i }
        val result = Future.find[Int](futures)(_ == 3)
        assertThat(Await.result(result, timeout.duration), equalTo(Some(3)))

        val notFound = Future.find[Int](futures)(_ == 11)
        assertThat(Await.result(notFound, timeout.duration), equalTo(None))
      }

      @Test def `must fold`: Unit = {
        assertThat(Await.result(Future.fold((1 to 10).toList map { i ⇒ Future(i) })(0)(_ + _), remaining), equalTo(55))
      }

      @Test def `must zip`: Unit = {
        val timeout = 10000 millis
        val f = new IllegalStateException("test")
        intercept[IllegalStateException] {
          Await.result(Promise.failed[String](f).future zip Promise.successful("foo").future, timeout)
        assertThat(}, equalTo(f))

        intercept[IllegalStateException] {
          Await.result(Promise.successful("foo").future zip Promise.failed[String](f).future, timeout)
        assertThat(}, equalTo(f))

        intercept[IllegalStateException] {
          Await.result(Promise.failed[String](f).future zip Promise.failed[String](f).future, timeout)
        assertThat(}, equalTo(f))

        assertThat(Await.result(Promise.successful("foo").future zip Promise.successful("foo").future, timeout), equalTo(("foo", "foo")))
      }

      @Test def `must fold by composing`: Unit = {
        val futures = (1 to 10).toList map { i ⇒ Future(i) }
        assertThat(Await.result(futures.foldLeft(Future(0))((fr, fa) ⇒ for (r ← fr; a ← fa) yield (r + a)), timeout.duration), equalTo(55))
      }

      @Test def `must fold with an exception`: Unit = {
        filterException[IllegalArgumentException] {
          val futures = (1 to 10).toList map {
            case 6 ⇒ Future(throw new IllegalArgumentException("shouldFoldResultsWithException: expected"))
            case i ⇒ Future(i)
          }
          assertThat(intercept[Throwable] { Await.result(Future.fold(futures)(0)(_ + _), remaining) }.getMessage, equalTo("shouldFoldResultsWithException: expected"))
        }
      }

      @Test def `must fold mutable zeroes safely`: Unit = {
        import scala.collection.mutable.ArrayBuffer
        def test(testNumber: Int) {
          val fs = (0 to 1000) map (i ⇒ Future(i))
          val f = Future.fold(fs)(ArrayBuffer.empty[AnyRef]) {
            case (l, i) if i % 2 == 0 ⇒ l += i.asInstanceOf[AnyRef]
            case (l, _)               ⇒ l
          }
          val result = Await.result(f.mapTo[ArrayBuffer[Int]], 10000 millis).sum

          assert(result === 250500)
        }

        (1 to 100) foreach test //Make sure it tries to provoke the problem
      }

      @Test def `must return zero value if folding empty list`: Unit = {
        assertThat(Await.result(Future.fold(List[Future[Int]]())(0)(_ + _), timeout.duration), equalTo(0))
      }

      @Test def `must reduce results`: Unit = {
        val futures = (1 to 10).toList map { i ⇒ Future(i) }
        assert(Await.result(Future.reduce(futures)(_ + _), remaining) === 55)
      }

      @Test def `must reduce results with Exception`: Unit = {
        filterException[IllegalArgumentException] {
          val futures = (1 to 10).toList map {
            case 6 ⇒ Future(throw new IllegalArgumentException("shouldReduceResultsWithException: expected"))
            case i ⇒ Future(i)
          }
          assertThat(intercept[Throwable] { Await.result(Future.reduce(futures)(_ + _), remaining) }.getMessage, equalTo("shouldReduceResultsWithException: expected"))
        }
      }

      @Test def `must throw IllegalArgumentException on empty input to reduce`: Unit = {
        filterException[IllegalArgumentException] {
          intercept[java.util.NoSuchElementException] { Await.result(Future.reduce(List[Future[Int]]())(_ + _), timeout.duration) }
        }
      }

      @Test def `must execute onSuccess when received ask reply`: Unit = {
        val latch = new TestLatch
        val actor = system.actorOf(Props[TestActor])
        actor ? "Hello" onSuccess { case "World" ⇒ latch.open() }
        FutureSpec.ready(latch, 5 seconds)
        system.stop(actor)
      }

      @Test def `must traverse Futures`: Unit = {
        val oddActor = system.actorOf(Props(new Actor {
          var counter = 1
          def receive = {
            case 'GetNext ⇒
              sender ! counter
              counter += 2
          }
        }))

        val oddFutures = List.fill(100)(oddActor ? 'GetNext mapTo classTag[Int])

        assert(Await.result(Future.sequence(oddFutures), timeout.duration).sum === 10000)
        system.stop(oddActor)

        val list = (1 to 100).toList
        assert(Await.result(Future.traverse(list)(x ⇒ Future(x * 2 - 1)), timeout.duration).sum === 10000)
      }

      @Test def `must handle Throwables`: Unit = {
        class ThrowableTest(m: String) extends Throwable(m)

        EventFilter[ThrowableTest](occurrences = 4) intercept {
          val f1 = Future[Any] { throw new ThrowableTest("test") }
          intercept[ThrowableTest] { Await.result(f1, timeout.duration) }

          val latch = new TestLatch
          val f2 = Future { FutureSpec.ready(latch, 5 seconds); "success" }
          f2 foreach (_ ⇒ throw new ThrowableTest("dispatcher foreach"))
          f2 onSuccess { case _ ⇒ throw new ThrowableTest("dispatcher receive") }
          val f3 = f2 map (s ⇒ s.toUpperCase)
          latch.open()
          assert(Await.result(f2, timeout.duration) === "success")
          f2 foreach (_ ⇒ throw new ThrowableTest("current thread foreach"))
          f2 onSuccess { case _ ⇒ throw new ThrowableTest("current thread receive") }
          assert(Await.result(f3, timeout.duration) === "SUCCESS")
        }
      }

      @Test def `must block until result`: Unit = {
        val latch = new TestLatch

        val f = Future { FutureSpec.ready(latch, 5 seconds); 5 }
        val f2 = Future { Await.result(f, timeout.duration) + 5 }

        intercept[TimeoutException](FutureSpec.ready(f2, 100 millis))
        latch.open()
        assert(Await.result(f2, timeout.duration) === 10)

        val f3 = Future { Thread.sleep(100); 5 }
        filterException[TimeoutException] { intercept[TimeoutException] { FutureSpec.ready(f3, 0 millis) } }
      }

      @Test def `must run callbacks async`: Unit = {
        val latch = Vector.fill(10)(new TestLatch)

        val f1 = Future { latch(0).open(); FutureSpec.ready(latch(1), TestLatch.DefaultTimeout); "Hello" }
        val f2 = f1 map { s ⇒ latch(2).open(); FutureSpec.ready(latch(3), TestLatch.DefaultTimeout); s.length }
        f2 foreach (_ ⇒ latch(4).open())

        FutureSpec.ready(latch(0), TestLatch.DefaultTimeout)

        assertThat(f1, not('completed))
        f2 must not be ('completed)

        latch(1).open()
        FutureSpec.ready(latch(2), TestLatch.DefaultTimeout)

        assertThat(f1, equalTo('completed))
        assertThat(f2, not('completed))

        val f3 = f1 map { s ⇒ latch(5).open(); FutureSpec.ready(latch(6), TestLatch.DefaultTimeout); s.length * 2 }
        f3 foreach (_ ⇒ latch(3).open())

        FutureSpec.ready(latch(5), TestLatch.DefaultTimeout)

        assertThat(f3, not('completed))

        latch(6).open()
        FutureSpec.ready(latch(4), TestLatch.DefaultTimeout)

        assertThat(f2, equalTo('completed))
        assertThat(f3, equalTo('completed))

        val p1 = Promise[String]()
        val f4 = p1.future map { s ⇒ latch(7).open(); FutureSpec.ready(latch(8), TestLatch.DefaultTimeout); s.length }
        f4 foreach (_ ⇒ latch(9).open())

        assertThat(p1, not('completed))
        f4 must not be ('completed)

        p1 complete Success("Hello")

        FutureSpec.ready(latch(7), TestLatch.DefaultTimeout)

        assertThat(p1, equalTo('completed))
        assertThat(f4, not('completed))

        latch(8).open()
        FutureSpec.ready(latch(9), TestLatch.DefaultTimeout)

        assertThat(FutureSpec.ready(f4, timeout.duration), equalTo('completed))
      }

      @Test def `must not deadlock with nested await (ticket 1313)`: Unit = {
        val simple = Future(()) map (_ ⇒ Await.result((Future(()) map (_ ⇒ ())), timeout.duration))
        assertThat(FutureSpec.ready(simple, timeout.duration), equalTo('completed))

        val l1, l2 = new TestLatch
        val complex = Future(()) map { _ ⇒
          val nested = Future(())
          nested foreach (_ ⇒ l1.open())
          FutureSpec.ready(l1, TestLatch.DefaultTimeout) // make sure nested is completed
          nested foreach (_ ⇒ l2.open())
          FutureSpec.ready(l2, TestLatch.DefaultTimeout)
        }
        assertThat(FutureSpec.ready(complex, timeout.duration), equalTo('completed))
      }

      @Test def `must re-use the same thread for nested futures with batching ExecutionContext`: Unit = {
        val failCount = new java.util.concurrent.atomic.AtomicInteger
        val f = Future(()) flatMap { _ ⇒
          val originalThread = Thread.currentThread
          // run some nested futures
          val nested =
            for (i ← 1 to 100)
              yield Future.successful("abc") flatMap { _ ⇒
              if (Thread.currentThread ne originalThread)
                failCount.incrementAndGet
              // another level of nesting
              Future.successful("xyz") map { _ ⇒
                if (Thread.currentThread ne originalThread)
                  failCount.incrementAndGet
              }
            }
          Future.sequence(nested)
        }
        Await.ready(f, timeout.duration)
        // TODO re-enable once we're using the batching dispatcher
        assertThat(// failCount.get, equalTo(0))
      }

    }
  }

  def emptyFuture(f: (Future[Any] ⇒ Unit) ⇒ Unit) {
    @Test def `must not be completed`: Unit = { f(_ must not be ('completed)) }
    @Test def `must not contain a value`: Unit = { f(_.value must be(None)) }
  }

  def futureWithResult(f: ((Future[Any], Any) ⇒ Unit) ⇒ Unit) {
    @Test def `must be completed`: Unit = { f((future, _) ⇒ future must be('completed)) }
    @Test def `must contain a value`: Unit = { f((future, result) ⇒ future.value must be(Some(Success(result)))) }
    @Test def `must return result with 'get'`: Unit = { f((future, result) ⇒ Await.result(future, timeout.duration) must be(result)) }
    @Test def `must return result with 'Await.result'`: Unit = { f((future, result) ⇒ Await.result(future, timeout.duration) must be(result)) }
    @Test def `must not timeout`: Unit = { f((future, _) ⇒ FutureSpec.ready(future, 0 millis)) }
    @Test def `must filter result`: Unit = {
      f { (future, result) ⇒
        assertThat(Await.result((future filter (_ ⇒ true)), timeout.duration), equalTo(result))
        evaluating { Await.result((future filter (_ ⇒ false)), timeout.duration) } must produce[java.util.NoSuchElementException]
      }
    }
    @Test def `must transform result with map`: Unit = { f((future, result) ⇒ Await.result((future map (_.toString.length)), timeout.duration) must be(result.toString.length)) }
    @Test def `must compose result with flatMap`: Unit = {
      f { (future, result) ⇒
        val r = for (r ← future; p ← Promise.successful("foo").future) yield r.toString + p
        assertThat(Await.result(r, timeout.duration), equalTo(result.toString + "foo"))
      }
    }
    @Test def `must perform action with foreach`: Unit = {
      f { (future, result) ⇒
        val p = Promise[Any]()
        future foreach p.success
        assertThat(Await.result(p.future, timeout.duration), equalTo(result))
      }
    }
    @Test def `must zip properly`: Unit = {
      f { (future, result) ⇒
        assertThat(Await.result(future zip Promise.successful("foo").future, timeout.duration), equalTo((result, "foo")))
        assertThat((evaluating { Await.result(future zip Promise.failed(new RuntimeException("ohnoes")).future, timeout.duration) } must produce[RuntimeException]).getMessage, equalTo("ohnoes"))
      }
    }
    @Test def `must not recover from exception`: Unit = { f((future, result) ⇒ Await.result(future.recover({ case _ ⇒ "pigdog" }), timeout.duration) must be(result)) }
    @Test def `must perform action on result`: Unit = {
      f { (future, result) ⇒
        val p = Promise[Any]()
        future.onSuccess { case x ⇒ p.success(x) }
        assertThat(Await.result(p.future, timeout.duration), equalTo(result))
      }
    }
    @Test def `must not project a failure`: Unit = { f((future, result) ⇒ (evaluating { Await.result(future.failed, timeout.duration) } must produce[NoSuchElementException]).getMessage must be("Future.failed not completed with a throwable.")) }
    "not perform action on exception" is pending
    @Test def `must cast using mapTo`: Unit = { f((future, result) ⇒ Await.result(future.mapTo[Boolean].recover({ case _: ClassCastException ⇒ false }), timeout.duration) must be(false)) }
  }

  def futureWithException[E <: Throwable: ClassTag](f: ((Future[Any], String) ⇒ Unit) ⇒ Unit) {
    @Test def `must be completed`: Unit = { f((future, _) ⇒ future must be('completed)) }
    @Test def `must contain a value`: Unit = {
      f((future, message) ⇒ {
        assertThat(future.value, equalTo('defined))
        assertThat(future.value.get, equalTo('failure))
        val Failure(f) = future.value.get
        assertThat(f.getMessage, equalTo(message))
      })
    }
    @Test def `must throw exception with 'get'`: Unit = { f((future, message) ⇒ (evaluating { Await.result(future, timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message)) }
    @Test def `must throw exception with 'Await.result'`: Unit = { f((future, message) ⇒ (evaluating { Await.result(future, timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message)) }
    @Test def `must retain exception with filter`: Unit = {
      f { (future, message) ⇒
        assertThat((evaluating { Await.result(future filter (_ ⇒ true), timeout.duration) } must produce[java.lang.Exception]).getMessage, equalTo(message))
        assertThat((evaluating { Await.result(future filter (_ ⇒ false), timeout.duration) } must produce[java.lang.Exception]).getMessage, equalTo(message))
      }
    }
    @Test def `must retain exception with map`: Unit = { f((future, message) ⇒ (evaluating { Await.result(future map (_.toString.length), timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message)) }
    @Test def `must retain exception with flatMap`: Unit = { f((future, message) ⇒ (evaluating { Await.result(future flatMap (_ ⇒ Promise.successful[Any]("foo").future), timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message)) }
    "not perform action with foreach" is pending

    @Test def `must zip properly`: Unit = {
      f { (future, message) ⇒ (evaluating { Await.result(future zip Promise.successful("foo").future, timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message) }
    }
    @Test def `must recover from exception`: Unit = { f((future, message) ⇒ Await.result(future.recover({ case e if e.getMessage == message ⇒ "pigdog" }), timeout.duration) must be("pigdog")) }
    "not perform action on result" is pending
    @Test def `must project a failure`: Unit = { f((future, message) ⇒ Await.result(future.failed, timeout.duration).getMessage must be(message)) }
    @Test def `must perform action on exception`: Unit = {
      f { (future, message) ⇒
        val p = Promise[Any]()
        future.onFailure { case _ ⇒ p.success(message) }
        assertThat(Await.result(p.future, timeout.duration), equalTo(message))
      }
    }
    @Test def `must always cast successfully using mapTo`: Unit = { f((future, message) ⇒ (evaluating { Await.result(future.mapTo[java.lang.Thread], timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message)) }
  }

  sealed trait IntAction { def apply(that: Int): Int }
  case class IntAdd(n: Int) extends IntAction { def apply(that: Int) = that + n }
  case class IntSub(n: Int) extends IntAction { def apply(that: Int) = that - n }
  case class IntMul(n: Int) extends IntAction { def apply(that: Int) = that * n }
  case class IntDiv(n: Int) extends IntAction { def apply(that: Int) = that / n }

  sealed trait FutureAction {
    def /:(that: Try[Int]): Try[Int]
    def /:(that: Future[Int]): Future[Int]
  }

  case class MapAction(action: IntAction) extends FutureAction {
    def /:(that: Try[Int]): Try[Int] = that map action.apply
    def /:(that: Future[Int]): Future[Int] = that map action.apply
  }

  case class FlatMapAction(action: IntAction) extends FutureAction {
    def /:(that: Try[Int]): Try[Int] = that map action.apply
    def /:(that: Future[Int]): Future[Int] = that flatMap (n ⇒ Future.successful(action(n)))
  }

  implicit def arbFuture: Arbitrary[Future[Int]] = Arbitrary(for (n ← arbitrary[Int]) yield Future(n))

  implicit def arbFutureAction: Arbitrary[FutureAction] = Arbitrary {

    val genIntAction = for {
      n ← arbitrary[Int]
      a ← oneOf(IntAdd(n), IntSub(n), IntMul(n), IntDiv(n))
    } yield a

    val genMapAction = genIntAction map (MapAction(_))

    val genFlatMapAction = genIntAction map (FlatMapAction(_))

    oneOf(genMapAction, genFlatMapAction)

  }

  def checkType[A: ClassTag, B](in: Future[A], reftag: ClassTag[B]): Boolean = implicitly[ClassTag[A]].runtimeClass == reftag.runtimeClass
}
