/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SwitchSpec {

  
    @Test def `must on and off`: Unit = {
      val s = new Switch(false)
      assertThat(s.isOff, equalTo(true))
      assertThat(s.isOn, equalTo(false))

      assertThat(s.switchOn(()), equalTo(true))
      assertThat(s.isOn, equalTo(true))
      assertThat(s.isOff, equalTo(false))
      assertThat(s.switchOn(()), equalTo(false))
      assertThat(s.isOn, equalTo(true))
      assertThat(s.isOff, equalTo(false))

      assertThat(s.switchOff(()), equalTo(true))
      assertThat(s.isOff, equalTo(true))
      assertThat(s.isOn, equalTo(false))
      assertThat(s.switchOff(()), equalTo(false))
      assertThat(s.isOff, equalTo(true))
      assertThat(s.isOn, equalTo(false))
    }

    @Test def `must revert when exception`: Unit = {
      val s = new Switch(false)
      intercept[RuntimeException] {
        s.switchOn(throw new RuntimeException)
      }
      assertThat(s.isOff, equalTo(true))
    }

    @Test def `must run action without locking`: Unit = {
      val s = new Switch(false)
      assertThat(s.ifOffYield("yes"), equalTo(Some("yes")))
      assertThat(s.ifOnYield("no"), equalTo(None))
      assertThat(s.ifOff(()), equalTo(true))
      assertThat(s.ifOn(()), equalTo(false))

      s.switchOn(())
      assertThat(s.ifOnYield("yes"), equalTo(Some("yes")))
      assertThat(s.ifOffYield("no"), equalTo(None))
      assertThat(s.ifOn(()), equalTo(true))
      assertThat(s.ifOff(()), equalTo(false))
    }

    @Test def `must run action with locking`: Unit = {
      val s = new Switch(false)
      assertThat(s.whileOffYield("yes"), equalTo(Some("yes")))
      assertThat(s.whileOnYield("no"), equalTo(None))
      assertThat(s.whileOff(()), equalTo(true))
      assertThat(s.whileOn(()), equalTo(false))

      s.switchOn(())
      assertThat(s.whileOnYield("yes"), equalTo(Some("yes")))
      assertThat(s.whileOffYield("no"), equalTo(None))
      assertThat(s.whileOn(()), equalTo(true))
      assertThat(s.whileOff(()), equalTo(false))
    }

    @Test def `must run first or second action depending on state`: Unit = {
      val s = new Switch(false)
      assertThat(s.fold("on")("off"), equalTo("off"))
      s.switchOn(())
      assertThat(s.fold("on")("off"), equalTo("on"))
    }

    @Test def `must do proper locking`: Unit = {
      val s = new Switch(false)

      s.locked {
        Thread.sleep(500)
        s.switchOn(())
        assertThat(s.isOn, equalTo(true))
      }

      val latch = new CountDownLatch(1)
      new Thread {
        override def run(): Unit = {
          s.switchOff(())
          latch.countDown()
        }
      }.start()

      latch.await(5, TimeUnit.SECONDS)
      assertThat(s.isOff, equalTo(true))
    }
  }