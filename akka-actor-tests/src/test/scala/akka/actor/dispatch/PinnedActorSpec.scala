package akka.actor.dispatch

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.testkit._
import akka.actor.{ Props, Actor }
import akka.testkit.AkkaSpec
import akka.dispatch.{ PinnedDispatcher, Dispatchers }
import scala.concurrent.Await
import akka.pattern.ask

object PinnedActorSpec {
  val config = """
    pinned-dispatcher {
      executor = thread-pool-executor
      type = PinnedDispatcher
    }
    """

  class TestActor extends Actor {
    def receive = {
      case "Hello"   ⇒ sender ! "World"
      case "Failure" ⇒ throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }
}

class PinnedActorSpec extends AkkaSpec(PinnedActorSpec.config) with BeforeAndAfterEach with DefaultTimeout {
  import PinnedActorSpec._

  private val unit = TimeUnit.MILLISECONDS

  
    @Test def `must support tell`: Unit = {
      var oneWay = new CountDownLatch(1)
      val actor = system.actorOf(Props(new Actor { def receive = { case "OneWay" ⇒ oneWay.countDown() } }).withDispatcher("pinned-dispatcher"))
      val result = actor ! "OneWay"
      assert(oneWay.await(1, TimeUnit.SECONDS))
      system.stop(actor)
    }

    @Test def `must support ask/reply`: Unit = {
      val actor = system.actorOf(Props[TestActor].withDispatcher("pinned-dispatcher"))
      assert("World" === Await.result(actor ? "Hello", timeout.duration))
      system.stop(actor)
    }
  }