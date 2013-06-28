/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.testkit.{ filterEvents, EventFilter }
import scala.concurrent.Await
import akka.dispatch.{ PinnedDispatcher, Dispatchers }
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import akka.testkit.AkkaSpec
import akka.testkit.DefaultTimeout
import akka.pattern.ask
import scala.concurrent.duration._
import scala.util.control.NonFatal

object SupervisorMiscSpec {
  val config = """
    pinned-dispatcher {
      executor = thread-pool-executor
      type = PinnedDispatcher
    }
    test-dispatcher {
    }
    """
}

class SupervisorMiscSpec extends AkkaSpec(SupervisorMiscSpec.config) with DefaultTimeout {

  
    @Test def `must restart a crashing actor and its dispatcher for any dispatcher`: Unit = {
      filterEvents(EventFilter[Exception]("Kill")) {
        val countDownLatch = new CountDownLatch(4)

        val supervisor = system.actorOf(Props(new Supervisor(
          OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 5 seconds)(List(classOf[Exception])))))

        val workerProps = Props(new Actor {
          override def postRestart(cause: Throwable) { countDownLatch.countDown() }
          def receive = {
            case "status" ⇒ this.sender ! "OK"
            case _        ⇒ this.context.stop(self)
          }
        })

        val actor1, actor2 = Await.result((supervisor ? workerProps.withDispatcher("pinned-dispatcher")).mapTo[ActorRef], timeout.duration)

        val actor3 = Await.result((supervisor ? workerProps.withDispatcher("test-dispatcher")).mapTo[ActorRef], timeout.duration)

        val actor4 = Await.result((supervisor ? workerProps.withDispatcher("pinned-dispatcher")).mapTo[ActorRef], timeout.duration)

        actor1 ! Kill
        actor2 ! Kill
        actor3 ! Kill
        actor4 ! Kill

        countDownLatch.await(10, TimeUnit.SECONDS)

        Seq("actor1" -> actor1, "actor2" -> actor2, "actor3" -> actor3, "actor4" -> actor4) map {
          case (id, ref) ⇒ (id, ref ? "status")
        } foreach {
          assertThat(case (id, f) ⇒ (id, Await.result(f, timeout.duration)), equalTo(((id, "OK"))))
        }
      }
    }

    @Test def `must be able to create named children in its constructor`: Unit = {
      val a = system.actorOf(Props(new Actor {
        context.actorOf(Props.empty, "bob")
        def receive = { case x: Exception ⇒ throw x }
        override def preStart(): Unit = testActor ! "preStart"
      }))
      val m = "weird message"
      EventFilter[Exception](m, occurrences = 1) intercept {
        a ! new Exception(m)
      }
      expectMsg("preStart")
      expectMsg("preStart")
      assertThat(a.isTerminated, equalTo(false))
    }

    @Test def `must be able to recreate child when old child is Terminated`: Unit = {
      val parent = system.actorOf(Props(new Actor {
        val kid = context.watch(context.actorOf(Props.empty, "foo"))
        def receive = {
          case Terminated(`kid`) ⇒
            try {
              val newKid = context.actorOf(Props.empty, "foo")
              val result =
                if (newKid eq kid) "Failure: context.actorOf returned the same instance!"
                else if (!kid.isTerminated) "Kid is zombie"
                else if (newKid.isTerminated) "newKid was stillborn"
                else if (kid.path != newKid.path) "The kids do not share the same path"
                else "green"
              testActor ! result
            } catch {
              case NonFatal(e) ⇒ testActor ! e
            }
          case "engage" ⇒ context.stop(kid)
        }
      }))
      parent ! "engage"
      expectMsg("green")
    }

    @Test def `must not be able to recreate child when old child is alive`: Unit = {
      val parent = system.actorOf(Props(new Actor {
        def receive = {
          case "engage" ⇒
            try {
              val kid = context.actorOf(Props.empty, "foo")
              context.stop(kid)
              context.actorOf(Props.empty, "foo")
              testActor ! "red"
            } catch {
              case e: InvalidActorNameException ⇒ testActor ! "green"
            }
        }
      }))
      parent ! "engage"
      expectMsg("green")
    }

    @Test def `must be able to create a similar kid in the fault handling strategy`: Unit = {
      val parent = system.actorOf(Props(new Actor {
        override val supervisorStrategy = new OneForOneStrategy()(SupervisorStrategy.defaultStrategy.decider) {
          override def handleChildTerminated(context: ActorContext, child: ActorRef, children: Iterable[ActorRef]): Unit = {
            val newKid = context.actorOf(Props.empty, child.path.name)
            testActor ! { if ((newKid ne child) && newKid.path == child.path) "green" else "red" }
          }
        }

        def receive = { case "engage" ⇒ context.stop(context.actorOf(Props.empty, "Robert")) }
      }))
      parent ! "engage"
      expectMsg("green")
      EventFilter[IllegalStateException]("handleChildTerminated failed", occurrences = 1) intercept {
        system.stop(parent)
      }
    }

    @Test def `must have access to the failing child’s reference in supervisorStrategy`: Unit = {
      val parent = system.actorOf(Props(new Actor {
        override val supervisorStrategy = OneForOneStrategy() {
          case _: Exception ⇒ testActor ! sender; SupervisorStrategy.Stop
        }
        def receive = {
          case "doit" ⇒ context.actorOf(Props.empty, "child") ! Kill
        }
      }))
      EventFilter[ActorKilledException](occurrences = 1) intercept {
        parent ! "doit"
      }
      val p = expectMsgType[ActorRef].path
      assertThat(p.parent, equalTo(parent.path))
      p.name must be === "child"
    }
  }