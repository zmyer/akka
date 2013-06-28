/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.junit.Test
import org.junit.experimental.categories.Category
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._
import akka.testkit.{ EventFilter, filterEvents, AkkaSpec }
import akka.util.Timeout
import akka.japi.{ Option ⇒ JOption }
import akka.testkit.DefaultTimeout
import akka.dispatch.Dispatchers
import akka.pattern.ask
import akka.serialization.JavaSerializer
import akka.actor.TypedActor._
import java.util.concurrent.atomic.AtomicReference
import java.lang.IllegalStateException
import java.util.concurrent.{ TimeoutException, TimeUnit, CountDownLatch }
import akka.testkit.TimingTest

object TypedActorSpec {

  val config = """
    pooled-dispatcher {
      type = BalancingDispatcher
      executor = "thread-pool-executor"
      thread-pool-executor {
        core-pool-size-min = 60
        core-pool-size-max = 60
        max-pool-size-min = 60
        max-pool-size-max = 60
      }
    }
    """

  class CyclicIterator[T](val items: immutable.Seq[T]) extends Iterator[T] {

    private[this] val current = new AtomicReference(items)

    def hasNext = items != Nil

    def next: T = {
      @tailrec
      def findNext: T = {
        val currentItems = current.get
        val newItems = currentItems match {
          case Nil ⇒ items
          case xs  ⇒ xs
        }

        if (current.compareAndSet(currentItems, newItems.tail)) newItems.head
        else findNext
      }

      findNext
    }

    override def exists(f: T ⇒ Boolean): Boolean = items exists f
  }

  trait Foo {
    def pigdog(): String

    @throws(classOf[TimeoutException])
    def self = TypedActor.self[Foo]

    def futurePigdog(): Future[String]

    def futurePigdog(delay: FiniteDuration): Future[String]

    def futurePigdog(delay: FiniteDuration, numbered: Int): Future[String]

    def futureComposePigdogFrom(foo: Foo): Future[String]

    def failingFuturePigdog(): Future[String] = throw new IllegalStateException("expected")

    @throws(classOf[TimeoutException])
    def failingOptionPigdog(): Option[String] = throw new IllegalStateException("expected")

    @throws(classOf[TimeoutException])
    def failingJOptionPigdog(): JOption[String] = throw new IllegalStateException("expected")

    def failingPigdog(): Unit = throw new IllegalStateException("expected")

    @throws(classOf[TimeoutException])
    def optionPigdog(): Option[String]

    @throws(classOf[TimeoutException])
    def optionPigdog(delay: FiniteDuration): Option[String]

    @throws(classOf[TimeoutException])
    def joptionPigdog(delay: FiniteDuration): JOption[String]

    def nullFuture(): Future[Any] = null

    def nullJOption(): JOption[Any] = null

    def nullOption(): Option[Any] = null

    def nullReturn(): Any = null

    def incr()

    @throws(classOf[TimeoutException])
    def read(): Int

    def testMethodCallSerialization(foo: Foo, s: String, i: Int): Unit = throw new IllegalStateException("expected")
  }

  class Bar extends Foo with Serializable {

    import TypedActor.dispatcher

    def pigdog = "Pigdog"

    def futurePigdog(): Future[String] = Promise.successful(pigdog).future

    def futurePigdog(delay: FiniteDuration): Future[String] = {
      Thread.sleep(delay.toMillis)
      futurePigdog
    }

    def futurePigdog(delay: FiniteDuration, numbered: Int): Future[String] = {
      Thread.sleep(delay.toMillis)
      Promise.successful(pigdog + numbered).future
    }

    def futureComposePigdogFrom(foo: Foo): Future[String] = {
      implicit val timeout = TypedActor(TypedActor.context.system).DefaultReturnTimeout
      foo.futurePigdog(500 millis).map(_.toUpperCase)
    }

    def optionPigdog(): Option[String] = Some(pigdog)

    def optionPigdog(delay: FiniteDuration): Option[String] = {
      Thread.sleep(delay.toMillis)
      Some(pigdog)
    }

    def joptionPigdog(delay: FiniteDuration): JOption[String] = {
      Thread.sleep(delay.toMillis)
      JOption.some(pigdog)
    }

    var internalNumber = 0

    def incr() {
      internalNumber += 1
    }

    def read() = internalNumber
  }

  trait Stackable1 {
    def stackable1: String = "foo"
  }

  trait Stackable2 {
    def stackable2: String = "bar"
  }

  trait Stacked extends Stackable1 with Stackable2 {
    def stacked: String = stackable1 + stackable2

    def notOverriddenStacked: String = stackable1 + stackable2
  }

  class StackedImpl extends Stacked {
    override def stacked: String = "FOOBAR" //Uppercase
  }

  trait LifeCycles {
    def crash(): Unit
  }

  class LifeCyclesImpl(val latch: CountDownLatch) extends PreStart with PostStop with PreRestart with PostRestart with LifeCycles with Receiver {

    private def ensureContextAvailable[T](f: ⇒ T): T = TypedActor.context match {
      case null ⇒ throw new IllegalStateException("TypedActor.context is null!")
      case some ⇒ f
    }

    override def crash(): Unit = throw new IllegalStateException("Crash!")

    override def preStart(): Unit = ensureContextAvailable(latch.countDown())

    override def postStop(): Unit = ensureContextAvailable(for (i ← 1 to 3) latch.countDown())

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = ensureContextAvailable(for (i ← 1 to 5) latch.countDown())

    override def postRestart(reason: Throwable): Unit = ensureContextAvailable(for (i ← 1 to 7) latch.countDown())

    override def onReceive(msg: Any, sender: ActorRef): Unit = {
      ensureContextAvailable(
        msg match {
          case "pigdog" ⇒ sender ! "dogpig"
        })
    }
  }

  trait F { def f(pow: Boolean): Int }
  class FI extends F { def f(pow: Boolean): Int = if (pow) throw new IllegalStateException("expected") else 1 }
}

class TypedActorSpec extends AkkaSpec(TypedActorSpec.config)  with DefaultTimeout {

  import TypedActorSpec._

  def newFooBar: Foo = newFooBar(timeout.duration)

  def newFooBar(d: FiniteDuration): Foo =
    TypedActor(system).typedActorOf(TypedProps[Bar](classOf[Foo], classOf[Bar]).withTimeout(Timeout(d)))

  def newFooBar(dispatcher: String, d: FiniteDuration): Foo =
    TypedActor(system).typedActorOf(TypedProps[Bar](classOf[Foo], classOf[Bar]).withTimeout(Timeout(d)).withDispatcher(dispatcher))

  def newStacked(): Stacked =
    TypedActor(system).typedActorOf(
      TypedProps[StackedImpl](classOf[Stacked], classOf[StackedImpl]).withTimeout(timeout))

  assertThat(def mustStop(typedActor: AnyRef) = TypedActor(system).stop(typedActor), equalTo(true))


    @Test def `must be able to instantiate`: Unit = {
      val t = newFooBar
      assertThat(TypedActor(system).isTypedActor(t), equalTo(true))
      mustStop(t)
    }

    @Test def `must be able to stop`: Unit = {
      val t = newFooBar
      mustStop(t)
    }

    @Test def `must not stop non-started ones`: Unit = {
      assertThat(TypedActor(system).stop(null), equalTo(false))
    }

    @Test def `must throw an IllegalStateExcpetion when TypedActor.self is called in the wrong scope`: Unit = {
      filterEvents(EventFilter[IllegalStateException]("Calling")) {
        (intercept[IllegalStateException] {
          TypedActor.self[Foo]
        assertThat(}).getMessage, equalTo("Calling TypedActor.self outside of a TypedActor implementation method!"))
      }
    }

    @Test def `must have access to itself when executing a method call`: Unit = {
      val t = newFooBar
      assertThat(t.self, equalTo(t))
      mustStop(t)
    }

    @Test def `must be able to call toString`: Unit = {
      val t = newFooBar
      assertThat(t.toString, equalTo(TypedActor(system).getActorRefFor(t).toString))
      mustStop(t)
    }

    @Test def `must be able to call equals`: Unit = {
      val t = newFooBar
      assertThat(t, equalTo(t))
      t must not equal (null)
      mustStop(t)
    }

    @Test def `must be able to call hashCode`: Unit = {
      val t = newFooBar
      assertThat(t.hashCode, equalTo(TypedActor(system).getActorRefFor(t).hashCode))
      mustStop(t)
    }

    @Test def `must be able to call user-defined void-methods`: Unit = {
      val t = newFooBar
      t.incr()
      assertThat(t.read(), equalTo(1))
      t.incr()
      assertThat(t.read(), equalTo(2))
      assertThat(t.read(), equalTo(2))
      mustStop(t)
    }

    @Test def `must be able to call normally returning methods`: Unit = {
      val t = newFooBar
      assertThat(t.pigdog(), equalTo("Pigdog"))
      mustStop(t)
    }

    @Test def `must be able to call null returning methods`: Unit = {
      val t = newFooBar
      assertThat(t.nullJOption(), equalTo(JOption.none))
      t.nullOption() must be === None
      assertThat(t.nullReturn(), equalTo(null))
      Await.result(t.nullFuture(), timeout.duration) must be === null
    }

    @Test def `must be able to call Future-returning methods non-blockingly`: Unit = {
      val t = newFooBar
      val f = t.futurePigdog(200 millis)
      assertThat(f.isCompleted, equalTo(false))
      assertThat(Await.result(f, timeout.duration), equalTo("Pigdog"))
      mustStop(t)
    }

    @Test def `must be able to call multiple Future-returning methods non-blockingly`: Unit = within(timeout.duration) {
      val t = newFooBar
      val futures = for (i ← 1 to 20) yield (i, t.futurePigdog(20 millis, i))
      for ((i, f) ← futures) {
        assertThat(Await.result(f, remaining), equalTo("Pigdog" + i))
      }
      mustStop(t)
    }

    @Test @Category(Array(classOf[TimingTest])) def `must be able to call methods returning Java Options`: Unit = {
      val t = newFooBar(1 second)
      assertThat(t.joptionPigdog(100 millis).get, equalTo("Pigdog"))
      assertThat(t.joptionPigdog(2 seconds), equalTo(JOption.none[String]))
      mustStop(t)
    }

    @Test @Category(Array(classOf[TimingTest])) def `must be able to handle AskTimeoutException as None`: Unit = {
      val t = newFooBar(200 millis)
      assertThat(t.joptionPigdog(600 millis), equalTo(JOption.none[String]))
      mustStop(t)
    }

    @Test @Category(Array(classOf[TimingTest])) def `must be able to call methods returning Scala Options`: Unit = {
      val t = newFooBar(1 second)
      assertThat(t.optionPigdog(100 millis).get, equalTo("Pigdog"))
      assertThat(t.optionPigdog(2 seconds), equalTo(None))
      mustStop(t)
    }

    @Test def `must be able to compose futures without blocking`: Unit = within(timeout.duration) {
      val t, t2 = newFooBar(remaining)
      val f = t.futureComposePigdogFrom(t2)
      assertThat(f.isCompleted, equalTo(false))
      assertThat(Await.result(f, remaining), equalTo("PIGDOG"))
      mustStop(t)
      mustStop(t2)
    }

    @Test def `must be able to handle exceptions when calling methods`: Unit = {
      filterEvents(EventFilter[IllegalStateException]("expected")) {
        val boss = system.actorOf(Props(new Actor {
          override val supervisorStrategy = OneForOneStrategy() {
            case e: IllegalStateException if e.getMessage == "expected" ⇒ SupervisorStrategy.Resume
          }
          def receive = {
            case p: TypedProps[_] ⇒ context.sender ! TypedActor(context).typedActorOf(p)
          }
        }))
        val t = Await.result((boss ? TypedProps[Bar](classOf[Foo], classOf[Bar]).withTimeout(2 seconds)).mapTo[Foo], timeout.duration)

        t.incr()
        t.failingPigdog()
        t.read() must be(1) //Make sure state is not reset after failure

        assertThat(intercept[IllegalStateException] { Await.result(t.failingFuturePigdog, 2 seconds) }.getMessage, equalTo("expected"))
        t.read() must be(1) //Make sure state is not reset after failure

        assertThat((intercept[IllegalStateException] { t.failingJOptionPigdog }).getMessage, equalTo("expected"))
        t.read() must be(1) //Make sure state is not reset after failure

        assertThat((intercept[IllegalStateException] { t.failingOptionPigdog }).getMessage, equalTo("expected"))

        t.read() must be(1) //Make sure state is not reset after failure

        mustStop(t)
      }
    }

    @Test def `must be restarted on failure`: Unit = {
      filterEvents(EventFilter[IllegalStateException]("expected")) {
        val t = newFooBar(Duration(2, "s"))
        assertThat(intercept[IllegalStateException] { t.failingOptionPigdog() }.getMessage, equalTo("expected"))
        t.optionPigdog() must be === Some("Pigdog")
        mustStop(t)

        val ta: F = TypedActor(system).typedActorOf(TypedProps[FI]())
        assertThat(intercept[IllegalStateException] { ta.f(true) }.getMessage, equalTo("expected"))
        ta.f(false) must be === 1

        mustStop(ta)
      }
    }

    @Test def `must be able to support stacked traits for the interface part`: Unit = {
      val t = newStacked()
      assertThat(t.notOverriddenStacked, equalTo("foobar"))
      assertThat(t.stacked, equalTo("FOOBAR"))
      mustStop(t)
    }

    @Test def `must be able to support implementation only typed actors`: Unit = within(timeout.duration) {
      val t: Foo = TypedActor(system).typedActorOf(TypedProps[Bar]())
      val f = t.futurePigdog(200 millis)
      val f2 = t.futurePigdog(Duration.Zero)
      assertThat(f2.isCompleted, equalTo(false))
      assertThat(f.isCompleted, equalTo(false))
      assertThat(Await.result(f, remaining), equalTo(Await.result(f2, remaining)))
      mustStop(t)
    }

    @Test def `must be able to support implementation only typed actors with complex interfaces`: Unit = {
      val t: Stackable1 with Stackable2 = TypedActor(system).typedActorOf(TypedProps[StackedImpl]())
      assertThat(t.stackable1, equalTo("foo"))
      assertThat(t.stackable2, equalTo("bar"))
      mustStop(t)
    }

    @Test def `must be able to use balancing dispatcher`: Unit = within(timeout.duration) {
      val thais = for (i ← 1 to 60) yield newFooBar("pooled-dispatcher", 6 seconds)
      val iterator = new CyclicIterator(thais)

      val results = for (i ← 1 to 120) yield (i, iterator.next.futurePigdog(200 millis, i))

      assertThat(for ((i, r) ← results) Await.result(r, remaining), equalTo("Pigdog" + i))

      for (t ← thais) mustStop(t)
    }

    @Test def `must be able to serialize and deserialize invocations`: Unit = {
      import java.io._
      JavaSerializer.currentSystem.withValue(system.asInstanceOf[ExtendedActorSystem]) {
        val m = TypedActor.MethodCall(classOf[Foo].getDeclaredMethod("pigdog"), Array[AnyRef]())
        val baos = new ByteArrayOutputStream(8192 * 4)
        val out = new ObjectOutputStream(baos)

        out.writeObject(m)
        out.close()

        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))

        val mNew = in.readObject().asInstanceOf[TypedActor.MethodCall]

        assertThat(mNew.method, equalTo(m.method))
      }
    }

    @Test def `must be able to serialize and deserialize invocations' parameters`: Unit = {
      import java.io._
      val someFoo: Foo = new Bar
      JavaSerializer.currentSystem.withValue(system.asInstanceOf[ExtendedActorSystem]) {
        val m = TypedActor.MethodCall(classOf[Foo].getDeclaredMethod("testMethodCallSerialization", Array[Class[_]](classOf[Foo], classOf[String], classOf[Int]): _*), Array[AnyRef](someFoo, null, 1.asInstanceOf[AnyRef]))
        val baos = new ByteArrayOutputStream(8192 * 4)
        val out = new ObjectOutputStream(baos)

        out.writeObject(m)
        out.close()

        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))

        val mNew = in.readObject().asInstanceOf[TypedActor.MethodCall]

        assertThat(mNew.method, equalTo(m.method))
        mNew.parameters must have size 3
        mNew.parameters(0) must not be null
        assertThat(mNew.parameters(0).getClass, equalTo(classOf[Bar]))
        assertThat(mNew.parameters(1), equalTo(null))
        mNew.parameters(2) must not be null
        assertThat(mNew.parameters(2).asInstanceOf[Int], equalTo(1))
      }
    }

    @Test def `must be able to serialize and deserialize proxies`: Unit = {
      import java.io._
      JavaSerializer.currentSystem.withValue(system.asInstanceOf[ExtendedActorSystem]) {
        val t = newFooBar(Duration(2, "s"))

        assertThat(t.optionPigdog(), equalTo(Some("Pigdog")))

        val baos = new ByteArrayOutputStream(8192 * 4)
        val out = new ObjectOutputStream(baos)

        out.writeObject(t)
        out.close()

        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))

        val tNew = in.readObject().asInstanceOf[Foo]

        assertThat(tNew, equalTo(t))

        assertThat(tNew.optionPigdog(), equalTo(Some("Pigdog")))

        mustStop(t)
      }
    }

    @Test def `must be able to override lifecycle callbacks`: Unit = {
      val latch = new CountDownLatch(16)
      val ta = TypedActor(system)
      val t: LifeCycles = ta.typedActorOf(TypedProps[LifeCyclesImpl](classOf[LifeCycles], new LifeCyclesImpl(latch)))
      EventFilter[IllegalStateException]("Crash!", occurrences = 1) intercept {
        t.crash()
      }

      //Sneak in a check for the Receiver override
      val ref = ta getActorRefFor t

      ref.tell("pigdog", testActor)

      expectMsg(timeout.duration, "dogpig")

      //Done with that now

      ta.poisonPill(t)
      assertThat(latch.await(10, TimeUnit.SECONDS), equalTo(true))
    }
  }