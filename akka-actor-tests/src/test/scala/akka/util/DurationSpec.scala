/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import scala.concurrent.duration._

import akka.testkit.AkkaSpec

class DurationSpec extends AkkaSpec {

  
    @Test def `must form a one-dimensional vector field`: Unit = {
      val zero = 0 seconds
      val one = 1 second
      val two = one + one
      val three = 3 * one
      assertThat((0 * one), equalTo(zero))
      assertThat((2 * one), equalTo(two))
      assertThat((three - two), equalTo(one))
      assertThat((three / 3), equalTo(one))
      assertThat((two / one), equalTo(2))
      assertThat((one + zero), equalTo(one))
      assertThat((one / 1000000), equalTo(1.micro))
    }

    @Test def `must respect correct treatment of infinities`: Unit = {
      val one = 1.second
      val inf = Duration.Inf
      val minf = Duration.MinusInf
      val undefined = Duration.Undefined
      assertThat((-inf), equalTo(minf))
      assertThat((minf + inf), equalTo(undefined))
      assertThat((inf - inf), equalTo(undefined))
      assertThat((inf + minf), equalTo(undefined))
      assertThat((minf - minf), equalTo(undefined))
      assertThat((inf + inf), equalTo(inf))
      assertThat((inf - minf), equalTo(inf))
      assertThat((minf - inf), equalTo(minf))
      assertThat((minf + minf), equalTo(minf))
      assert(inf == inf)
      assert(minf == minf)
      assertThat(inf.compareTo(inf), equalTo(0))
      assertThat(inf.compareTo(one), equalTo(1))
      assertThat(minf.compareTo(minf), equalTo(0))
      assertThat(minf.compareTo(one), equalTo(-1))
      assert(inf != minf)
      assert(minf != inf)
      assert(one != inf)
      assert(minf != one)
    }

    /*@Test def `must check its range`: Unit = {
      for (unit ← Seq(DAYS, HOURS, MINUTES, SECONDS, MILLISECONDS, MICROSECONDS, NANOSECONDS)) {
        val x = unit.convert(Long.MaxValue, NANOSECONDS)
        val dur = Duration(x, unit)
        val mdur = Duration(-x, unit)
        assertThat(-mdur, equalTo(dur))
        intercept[IllegalArgumentException] { Duration(x + 10000000d, unit) }
        intercept[IllegalArgumentException] { Duration(-x - 10000000d, unit) }
        if (unit != NANOSECONDS) {
          intercept[IllegalArgumentException] { Duration(x + 1, unit) }
          intercept[IllegalArgumentException] { Duration(-x - 1, unit) }
        }
        intercept[IllegalArgumentException] { dur + 1.day }
        intercept[IllegalArgumentException] { mdur - 1.day }
        intercept[IllegalArgumentException] { dur * 1.1 }
        intercept[IllegalArgumentException] { mdur * 1.1 }
        intercept[IllegalArgumentException] { dur * 2.1 }
        intercept[IllegalArgumentException] { mdur * 2.1 }
        intercept[IllegalArgumentException] { dur / 0.9 }
        intercept[IllegalArgumentException] { mdur / 0.9 }
        intercept[IllegalArgumentException] { dur / 0.4 }
        intercept[IllegalArgumentException] { mdur / 0.4 }
        Duration(x + unit.toString.toLowerCase)
        Duration("-" + x + unit.toString.toLowerCase)
        intercept[IllegalArgumentException] { Duration("%.0f".format(x + 10000000d) + unit.toString.toLowerCase) }
        intercept[IllegalArgumentException] { Duration("-%.0f".format(x + 10000000d) + unit.toString.toLowerCase) }
      }
    }*/

    @Test def `must support fromNow`: Unit = {
      val dead = 2.seconds.fromNow
      val dead2 = 2 seconds fromNow
      // view bounds vs. very local type inference vs. operator precedence: sigh
      dead.timeLeft must be > (1 second: Duration)
      dead2.timeLeft must be > (1 second: Duration)
      Thread.sleep(1.second.toMillis)
      dead.timeLeft must be < (1 second: Duration)
      dead2.timeLeft must be < (1 second: Duration)
    }

  }