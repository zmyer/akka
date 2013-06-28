/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.actor._
import scala.concurrent.duration._

class TestFSMRefSpec extends AkkaSpec {

  
    @Test def `must allow access to state data`: Unit = {
      val fsm = TestFSMRef(new Actor with FSM[Int, String] {
        startWith(1, "")
        when(1) {
          case Event("go", _)         ⇒ goto(2) using "go"
          case Event(StateTimeout, _) ⇒ goto(2) using "timeout"
        }
        when(2) {
          case Event("back", _) ⇒ goto(1) using "back"
        }
      }, "test-fsm-ref-1")
      assertThat(fsm.stateName, equalTo(1))
      assertThat(fsm.stateData, equalTo(""))
      fsm ! "go"
      assertThat(fsm.stateName, equalTo(2))
      assertThat(fsm.stateData, equalTo("go"))
      fsm.setState(stateName = 1)
      assertThat(fsm.stateName, equalTo(1))
      assertThat(fsm.stateData, equalTo("go"))
      fsm.setState(stateData = "buh")
      assertThat(fsm.stateName, equalTo(1))
      assertThat(fsm.stateData, equalTo("buh"))
      fsm.setState(timeout = 100 millis)
      within(80 millis, 500 millis) {
        awaitCond(fsm.stateName == 2 && fsm.stateData == "timeout")
      }
    }

    @Test def `must allow access to timers`: Unit = {
      val fsm = TestFSMRef(new Actor with FSM[Int, Null] {
        startWith(1, null)
        when(1) {
          case x ⇒ stay
        }
      }, "test-fsm-ref-2")
      assertThat(fsm.isTimerActive("test"), equalTo(false))
      fsm.setTimer("test", 12, 10 millis, true)
      assertThat(fsm.isTimerActive("test"), equalTo(true))
      fsm.cancelTimer("test")
      assertThat(fsm.isTimerActive("test"), equalTo(false))
    }
  }