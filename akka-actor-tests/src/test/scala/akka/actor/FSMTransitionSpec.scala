/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.testkit._
import scala.concurrent.duration._

object FSMTransitionSpec {

  class Supervisor extends Actor {
    def receive = { case _ ⇒ }
  }

  class MyFSM(target: ActorRef) extends Actor with FSM[Int, Unit] {
    startWith(0, Unit)
    when(0) {
      case Event("tick", _) ⇒ goto(1)
    }
    when(1) {
      case Event("tick", _) ⇒ goto(0)
    }
    whenUnhandled {
      case Event("reply", _) ⇒ stay replying "reply"
    }
    initialize()
    override def preRestart(reason: Throwable, msg: Option[Any]) { target ! "restarted" }
  }

  class OtherFSM(target: ActorRef) extends Actor with FSM[Int, Int] {
    startWith(0, 0)
    when(0) {
      case Event("tick", _) ⇒ goto(1) using (1)
    }
    when(1) {
      case _ ⇒ stay
    }
    onTransition {
      case 0 -> 1 ⇒ target ! ((stateData, nextStateData))
    }
  }

  class Forwarder(target: ActorRef) extends Actor {
    def receive = { case x ⇒ target ! x }
  }

}

class FSMTransitionSpec extends AkkaSpec with ImplicitSender {

  import FSMTransitionSpec._

  
    @Test def `must notify listeners`: Unit = {
      import FSM.{ SubscribeTransitionCallBack, CurrentState, Transition }

      val fsm = system.actorOf(Props(new MyFSM(testActor)))
      within(1 second) {
        fsm ! SubscribeTransitionCallBack(testActor)
        expectMsg(CurrentState(fsm, 0))
        fsm ! "tick"
        expectMsg(Transition(fsm, 0, 1))
        fsm ! "tick"
        expectMsg(Transition(fsm, 1, 0))
      }
    }

    @Test def `must not fail when listener goes away`: Unit = {
      val forward = system.actorOf(Props(new Forwarder(testActor)))
      val fsm = system.actorOf(Props(new MyFSM(testActor)))

      within(1 second) {
        fsm ! FSM.SubscribeTransitionCallBack(forward)
        expectMsg(FSM.CurrentState(fsm, 0))
        akka.pattern.gracefulStop(forward, 5 seconds)
        fsm ! "tick"
        expectNoMsg
      }
    }
  }

  
    @Test def `must make previous and next state data available in onTransition`: Unit = {
      val fsm = system.actorOf(Props(new OtherFSM(testActor)))
      within(1 second) {
        fsm ! "tick"
        expectMsg((0, 1))
      }
    }

    @Test def `must not leak memory in nextState`: Unit = {
      val fsmref = system.actorOf(Props(new Actor with FSM[Int, ActorRef] {
        startWith(0, null)
        when(0) {
          case Event("switch", _) ⇒ goto(1) using sender
        }
        onTransition {
          case x -> y ⇒ nextStateData ! (x -> y)
        }
        when(1) {
          case Event("test", _) ⇒
            try {
              sender ! s"failed: ${nextStateData}"
            } catch {
              case _: IllegalStateException ⇒ sender ! "ok"
            }
            stay
        }
      }))
      fsmref ! "switch"
      expectMsg((0, 1))
      fsmref ! "test"
      expectMsg("ok")
    }
  }