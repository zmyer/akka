/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps
import akka.dispatch.sysmsg.{ DeathWatchNotification, Failed }
import akka.pattern.ask
import akka.testkit._
import scala.concurrent.duration._
import scala.concurrent.Await

class LocalDeathWatchSpec extends AkkaSpec with ImplicitSender with DefaultTimeout with DeathWatchSpec

object DeathWatchSpec {
  def props(target: ActorRef, testActor: ActorRef) = Props(new Actor {
    context.watch(target)
    def receive = {
      case t: Terminated ⇒ testActor forward WrappedTerminated(t)
      case x             ⇒ testActor forward x
    }
  })

  /**
   * Forwarding `Terminated` to non-watching testActor is not possible,
   * and therefore the `Terminated` message is wrapped.
   */
  case class WrappedTerminated(t: Terminated)
}

trait DeathWatchSpec { this: AkkaSpec with ImplicitSender with DefaultTimeout ⇒

  import DeathWatchSpec._

  lazy val supervisor = system.actorOf(Props(new Supervisor(SupervisorStrategy.defaultStrategy)), "watchers")

  def startWatching(target: ActorRef) = Await.result((supervisor ? props(target, testActor)).mapTo[ActorRef], 3 seconds)

      def expectTerminationOf(actorRef: ActorRef) = expectMsgPF(5 seconds, actorRef + ": Stopped or Already terminated when linking") {
      case WrappedTerminated(Terminated(`actorRef`)) ⇒ true
    }

    @Test def `must notify with one Terminated message when an Actor is stopped`: Unit = {
      val terminal = system.actorOf(Props.empty)
      startWatching(terminal) ! "hallo"
      expectMsg("hallo")

      terminal ! PoisonPill

      expectTerminationOf(terminal)
    }

    @Test def `must notify with one Terminated message when an Actor is already dead`: Unit = {
      val terminal = system.actorOf(Props.empty)

      terminal ! PoisonPill

      startWatching(terminal)
      expectTerminationOf(terminal)
    }

    @Test def `must notify with all monitors with one Terminated message when an Actor is stopped`: Unit = {
      val terminal = system.actorOf(Props.empty)
      val monitor1, monitor2, monitor3 = startWatching(terminal)

      terminal ! PoisonPill

      expectTerminationOf(terminal)
      expectTerminationOf(terminal)
      expectTerminationOf(terminal)

      system.stop(monitor1)
      system.stop(monitor2)
      system.stop(monitor3)
    }

    @Test def `must notify with _current_ monitors with one Terminated message when an Actor is stopped`: Unit = {
      val terminal = system.actorOf(Props.empty)
      val monitor1, monitor3 = startWatching(terminal)
      val monitor2 = system.actorOf(Props(new Actor {
        context.watch(terminal)
        context.unwatch(terminal)
        def receive = {
          case "ping"        ⇒ sender ! "pong"
          case t: Terminated ⇒ testActor ! WrappedTerminated(t)
        }
      }).withDeploy(Deploy.local))

      monitor2 ! "ping"

      expectMsg("pong") //Needs to be here since watch and unwatch are asynchronous

      terminal ! PoisonPill

      expectTerminationOf(terminal)
      expectTerminationOf(terminal)

      system.stop(monitor1)
      system.stop(monitor2)
      system.stop(monitor3)
    }

    @Test def `must notify with a Terminated message once when an Actor is stopped but not when restarted`: Unit = {
      filterException[ActorKilledException] {
        val supervisor = system.actorOf(Props(new Supervisor(
          OneForOneStrategy(maxNrOfRetries = 2)(List(classOf[Exception])))))
        val terminalProps = Props(new Actor { def receive = { case x ⇒ sender ! x } })
        val terminal = Await.result((supervisor ? terminalProps).mapTo[ActorRef], timeout.duration)

        val monitor = startWatching(terminal)

        terminal ! Kill
        terminal ! Kill
        assertThat(Await.result(terminal ? "foo", timeout.duration), equalTo("foo"))
        terminal ! Kill

        expectTerminationOf(terminal)
        assertThat(terminal.isTerminated, equalTo(true))

        system.stop(supervisor)
      }
    }

    @Test def `must fail a monitor which does not handle Terminated()`: Unit = {
      filterEvents(EventFilter[ActorKilledException](), EventFilter[DeathPactException]()) {
        case class FF(fail: Failed)
        val strategy = new OneForOneStrategy()(SupervisorStrategy.defaultStrategy.decider) {
          override def handleFailure(context: ActorContext, child: ActorRef, cause: Throwable, stats: ChildRestartStats, children: Iterable[ChildRestartStats]) = {
            testActor.tell(FF(Failed(child, cause, 0)), child)
            super.handleFailure(context, child, cause, stats, children)
          }
        }
        val supervisor = system.actorOf(Props(new Supervisor(strategy)).withDeploy(Deploy.local))

        val failed = Await.result((supervisor ? Props.empty).mapTo[ActorRef], timeout.duration)
        val brother = Await.result((supervisor ? Props(new Actor {
          context.watch(failed)
          def receive = Actor.emptyBehavior
        })).mapTo[ActorRef], timeout.duration)

        startWatching(brother)

        failed ! Kill
        val result = receiveWhile(3 seconds, messages = 3) {
          case FF(Failed(_, _: ActorKilledException, _)) if lastSender eq failed       ⇒ 1
          case FF(Failed(_, DeathPactException(`failed`), _)) if lastSender eq brother ⇒ 2
          case WrappedTerminated(Terminated(`brother`))                                ⇒ 3
        }
        testActor.isTerminated must not be true
        assertThat(result, equalTo(Seq(1, 2, 3)))
      }
    }

    @Test def `must be able to watch a child with the same name after the old died`: Unit = {
      val parent = system.actorOf(Props(new Actor {
        def receive = {
          case "NKOTB" ⇒
            val currentKid = context.watch(context.actorOf(Props(new Actor { def receive = { case "NKOTB" ⇒ context stop self } }), "kid"))
            currentKid forward "NKOTB"
            context become {
              case Terminated(`currentKid`) ⇒
                testActor ! "GREEN"
                context unbecome
            }
        }
      }).withDeploy(Deploy.local))

      parent ! "NKOTB"
      expectMsg("GREEN")
      parent ! "NKOTB"
      expectMsg("GREEN")
    }

    @Test def `must only notify when watching`: Unit = {
      val subject = system.actorOf(Props(new Actor { def receive = Actor.emptyBehavior }))

      testActor.asInstanceOf[InternalActorRef]
        .sendSystemMessage(DeathWatchNotification(subject, existenceConfirmed = true, addressTerminated = false))

      // the testActor is not watching subject and will not receive a Terminated msg
      expectNoMsg
    }

    @Test def `must discard Terminated when unwatched between sysmsg and processing`: Unit = {
      case class W(ref: ActorRef)
      case class U(ref: ActorRef)
      class Watcher extends Actor {
        def receive = {
          case W(ref) ⇒ context watch ref
          case U(ref) ⇒ context unwatch ref
          case (t1: TestLatch, t2: TestLatch) ⇒
            t1.countDown()
            Await.ready(t2, 3.seconds)
        }
      }

      val t1, t2 = TestLatch()
      val w = system.actorOf(Props(new Watcher).withDeploy(Deploy.local), "myDearWatcher")
      val p = TestProbe()
      w ! W(p.ref)
      w ! ((t1, t2))
      Await.ready(t1, 3.seconds)
      watch(p.ref)
      system stop p.ref
      expectTerminated(p.ref)
      w ! U(p.ref)
      t2.countDown()
      /*
       * now the Watcher will
       * - process the DeathWatchNotification and enqueue Terminated
       * - process the unwatch command
       * - process the Terminated
       * If it receives the Terminated it will die, which in fact it should not
       */
      w ! Identify(())
      expectMsg(ActorIdentity((), Some(w)))
      w ! Identify(())
      expectMsg(ActorIdentity((), Some(w)))
    }
  }