/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.testkit._
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import java.lang.IllegalStateException
import scala.concurrent.Promise
import akka.pattern.ask
import akka.serialization.JavaSerializer
import akka.TestUtils.verifyActorTermination

object ActorRefSpec {

  case class ReplyTo(sender: ActorRef)

  class ReplyActor extends Actor {
    var replyTo: ActorRef = null

    def receive = {
      case "complexRequest" ⇒ {
        replyTo = sender
        val worker = context.actorOf(Props[WorkerActor])
        worker ! "work"
      }
      case "complexRequest2" ⇒
        val worker = context.actorOf(Props[WorkerActor])
        worker ! ReplyTo(sender)
      case "workDone"      ⇒ replyTo ! "complexReply"
      case "simpleRequest" ⇒ sender ! "simpleReply"
    }
  }

  class WorkerActor() extends Actor {
    import context.system
    def receive = {
      case "work" ⇒ {
        work()
        sender ! "workDone"
        context.stop(self)
      }
      case ReplyTo(replyTo) ⇒ {
        work()
        replyTo ! "complexReply"
      }
    }

    private def work(): Unit = Thread.sleep(1.second.dilated.toMillis)
  }

  class SenderActor(replyActor: ActorRef, latch: TestLatch) extends Actor {

    def receive = {
      case "complex"  ⇒ replyActor ! "complexRequest"
      case "complex2" ⇒ replyActor ! "complexRequest2"
      case "simple"   ⇒ replyActor ! "simpleRequest"
      case "complexReply" ⇒ {
        latch.countDown()
      }
      case "simpleReply" ⇒ {
        latch.countDown()
      }
    }
  }

  class OuterActor(val inner: ActorRef) extends Actor {
    def receive = {
      case "self" ⇒ sender ! self
      case x      ⇒ inner forward x
    }
  }

  class FailingOuterActor(val inner: ActorRef) extends Actor {
    val fail = new InnerActor

    def receive = {
      case "self" ⇒ sender ! self
      case x      ⇒ inner forward x
    }
  }

  class FailingInheritingOuterActor(_inner: ActorRef) extends OuterActor(_inner) {
    val fail = new InnerActor
  }

  class InnerActor extends Actor {
    def receive = {
      case "innerself" ⇒ sender ! self
      case other       ⇒ sender ! other
    }
  }

  class FailingInnerActor extends Actor {
    val fail = new InnerActor

    def receive = {
      case "innerself" ⇒ sender ! self
      case other       ⇒ sender ! other
    }
  }

  class FailingInheritingInnerActor extends InnerActor {
    val fail = new InnerActor
  }
}

class ActorRefSpec extends AkkaSpec with DefaultTimeout {
  import akka.actor.ActorRefSpec._

  def promiseIntercept(f: ⇒ Actor)(to: Promise[Actor]): Actor = try {
    val r = f
    to.success(r)
    r
  } catch {
    case e: Throwable ⇒
      to.failure(e)
      throw e
  }

  def wrap[T](f: Promise[Actor] ⇒ T): T = {
    val result = Promise[Actor]()
    val r = f(result)
    Await.result(result.future, 1 minute)
    r
  }

  
    @Test def `must not allow Actors to be created outside of an actorOf`: Unit = {
      import system.actorOf
      intercept[akka.actor.ActorInitializationException] {
        new Actor { def receive = { case _ ⇒ } }
      }

      assertThat(def contextStackMustBeEmpty(): Unit = ActorCell.contextStack.get.headOption, equalTo(None))

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new Actor {
              val nested = promiseIntercept(new Actor { def receive = { case _ ⇒ } })(result)
              def receive = { case _ ⇒ }
            })))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(promiseIntercept(new FailingOuterActor(actorOf(Props(new InnerActor))))(result))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(promiseIntercept(new FailingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(promiseIntercept(new FailingInheritingOuterActor(actorOf(Props(new InnerActor))))(result))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 2) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new FailingOuterActor(actorOf(Props(promiseIntercept(new FailingInheritingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 2) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new FailingInheritingOuterActor(actorOf(Props(promiseIntercept(new FailingInheritingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 2) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new FailingInheritingOuterActor(actorOf(Props(promiseIntercept(new FailingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(new InnerActor {
              val a = promiseIntercept(new InnerActor)(result)
            }))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 2) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new FailingOuterActor(actorOf(Props(promiseIntercept(new FailingInheritingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(promiseIntercept(new FailingInheritingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(promiseIntercept({ new InnerActor; new InnerActor })(result)))))))
        }

        contextStackMustBeEmpty()
      }

      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        (intercept[java.lang.IllegalStateException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(promiseIntercept({ throw new IllegalStateException("Ur state be b0rked"); new InnerActor })(result)))))))
        assertThat(}).getMessage, equalTo("Ur state be b0rked"))

        contextStackMustBeEmpty()
      }
    }

    @Test def `must be serializable using Java Serialization on local node`: Unit = {
      val a = system.actorOf(Props[InnerActor])
      val esys = system.asInstanceOf[ExtendedActorSystem]

      import java.io._

      val baos = new ByteArrayOutputStream(8192 * 32)
      val out = new ObjectOutputStream(baos)

      out.writeObject(a)

      out.flush
      out.close

      val bytes = baos.toByteArray

      JavaSerializer.currentSystem.withValue(esys) {
        val in = new ObjectInputStream(new ByteArrayInputStream(bytes))
        val readA = in.readObject

        assertThat(a.isInstanceOf[ActorRefWithCell], equalTo(true))
        readA.isInstanceOf[ActorRefWithCell] must be === true
        assertThat((readA eq a), equalTo(true))
      }

      val ser = new JavaSerializer(esys)
      val readA = ser.fromBinary(bytes, None)
      assertThat(readA.isInstanceOf[ActorRefWithCell], equalTo(true))
      (readA eq a) must be === true
    }

    @Test def `must throw an exception on deserialize if no system in scope`: Unit = {
      val a = system.actorOf(Props[InnerActor])

      import java.io._

      val baos = new ByteArrayOutputStream(8192 * 32)
      val out = new ObjectOutputStream(baos)

      out.writeObject(a)

      out.flush
      out.close

      val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))

      (intercept[java.lang.IllegalStateException] {
        in.readObject
      assertThat(}).getMessage, equalTo("Trying to deserialize a serialized ActorRef without an ActorSystem in scope." +))
        " Use 'akka.serialization.Serialization.currentSystem.withValue(system) { ... }'"
    }

    @Test def `must return EmptyLocalActorRef on deserialize if not present in actor hierarchy (and remoting is not enabled)`: Unit = {
      import java.io._

      val baos = new ByteArrayOutputStream(8192 * 32)
      val out = new ObjectOutputStream(baos)

      val sysImpl = system.asInstanceOf[ActorSystemImpl]
      val ref = system.actorOf(Props[ReplyActor], "non-existing")
      val serialized = SerializedActorRef(ref)

      out.writeObject(serialized)

      out.flush
      out.close

      ref ! PoisonPill

      verifyActorTermination(ref)

      JavaSerializer.currentSystem.withValue(sysImpl) {
        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))
        assertThat(in.readObject, equalTo(new EmptyLocalActorRef(sysImpl.provider, ref.path, system.eventStream)))
      }
    }

    @Test def `must support nested actorOfs`: Unit = {
      val a = system.actorOf(Props(new Actor {
        val nested = system.actorOf(Props(new Actor { def receive = { case _ ⇒ } }))
        def receive = { case _ ⇒ sender ! nested }
      }))

      val nested = Await.result((a ? "any").mapTo[ActorRef], timeout.duration)
      a must not be null
      nested must not be null
      assertThat((a ne nested), equalTo(true))
    }

    @Test def `must support advanced nested actorOfs`: Unit = {
      val a = system.actorOf(Props(new OuterActor(system.actorOf(Props(new InnerActor)))))
      val inner = Await.result(a ? "innerself", timeout.duration)

      assertThat(Await.result(a ? a, timeout.duration), equalTo(a))
      assertThat(Await.result(a ? "self", timeout.duration), equalTo(a))
      inner must not be a

      assertThat(Await.result(a ? "msg", timeout.duration), equalTo("msg"))
    }

    @Test def `must support reply via sender`: Unit = {
      val latch = new TestLatch(4)
      val serverRef = system.actorOf(Props[ReplyActor])
      val clientRef = system.actorOf(Props(new SenderActor(serverRef, latch)))

      clientRef ! "complex"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"

      Await.ready(latch, timeout.duration)

      latch.reset

      clientRef ! "complex2"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"

      Await.ready(latch, timeout.duration)

      system.stop(clientRef)
      system.stop(serverRef)
    }

    @Test def `must support actorOfs where the class of the actor isn't public`: Unit = {
      val a = system.actorOf(NonPublicClass.createProps())
      a.tell("pigdog", testActor)
      expectMsg("pigdog")
      system stop a
    }

    @Test def `must stop when sent a poison pill`: Unit = {
      val timeout = Timeout(20000)
      val ref = system.actorOf(Props(new Actor {
        def receive = {
          case 5 ⇒ sender ! "five"
          case 0 ⇒ sender ! "null"
        }
      }))

      val ffive = (ref.ask(5)(timeout)).mapTo[String]
      val fnull = (ref.ask(0)(timeout)).mapTo[String]
      ref ! PoisonPill

      assertThat(Await.result(ffive, timeout.duration), equalTo("five"))
      assertThat(Await.result(fnull, timeout.duration), equalTo("null"))

      verifyActorTermination(ref)
    }

    @Test def `must restart when Kill:ed`: Unit = {
      filterException[ActorKilledException] {
        val latch = TestLatch(2)

        val boss = system.actorOf(Props(new Actor {

          override val supervisorStrategy =
            OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 1 second)(List(classOf[Throwable]))

          val ref = context.actorOf(
            Props(new Actor {
              def receive = { case _ ⇒ }
              override def preRestart(reason: Throwable, msg: Option[Any]) = latch.countDown()
              override def postRestart(reason: Throwable) = latch.countDown()
            }))

          def receive = { case "sendKill" ⇒ ref ! Kill }
        }))

        boss ! "sendKill"
        Await.ready(latch, 5 seconds)
      }
    }

    @Test def `must be able to check for existence of children`: Unit = {
      val parent = system.actorOf(Props(new Actor {

        val child = context.actorOf(
          Props(new Actor {
            def receive = { case _ ⇒ }
          }), "child")

        def receive = { case name: String ⇒ sender ! context.child(name).isDefined }
      }), "parent")

      assert(Await.result((parent ? "child"), remaining) === true)
      assert(Await.result((parent ? "whatnot"), remaining) === false)
    }
  }