package akka.testkit

import org.junit.Test
import org.junit.experimental.categories.Category
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._
import scala.concurrent.duration._
import com.typesafe.config.Config

class TestTimeSpec extends AkkaSpec(Map("akka.test.timefactor" -> 2.0)) {

  @Test @Category(Array(classOf[TimingTest])) def `must correctly dilate times`: Unit = {
    val probe = TestProbe()
    val now = System.nanoTime
    intercept[AssertionError] { probe.awaitCond(false, Duration("1 second")) }
    val diff = System.nanoTime - now
    val target = (1000000000l * testKitSettings.TestTimeFactor).toLong
    diff must be > (target - 300000000l)
    diff must be < (target + 300000000l)
  }

  @Test def `must awaitAssert must throw correctly`: Unit = {
    assertThat(awaitAssert("foo"), equalTo("foo"))
    within(300.millis, 2.seconds) {
      intercept[TestFailedException] {
        assertThat(awaitAssert("foo", equalTo("bar"), 500.millis, 300.millis))
      }
    }
  }

}