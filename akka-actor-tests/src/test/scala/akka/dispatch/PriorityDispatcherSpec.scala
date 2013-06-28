package akka.dispatch

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import org.junit.runner.RunWith
import com.typesafe.config.Config

import akka.actor.{ Props, ActorSystem, Actor }
import akka.pattern.ask
import akka.testkit.{ DefaultTimeout, AkkaSpec }
import scala.concurrent.duration._

object PriorityDispatcherSpec {
  val config = """
    unbounded-prio-dispatcher {
      mailbox-type = "akka.dispatch.PriorityDispatcherSpec$Unbounded"
    }
    bounded-prio-dispatcher {
      mailbox-type = "akka.dispatch.PriorityDispatcherSpec$Bounded"
    }
    """

  class Unbounded(settings: ActorSystem.Settings, config: Config) extends UnboundedPriorityMailbox(PriorityGenerator({
    case i: Int  ⇒ i //Reverse order
    case 'Result ⇒ Int.MaxValue
  }: Any ⇒ Int))

  class Bounded(settings: ActorSystem.Settings, config: Config) extends BoundedPriorityMailbox(PriorityGenerator({
    case i: Int  ⇒ i //Reverse order
    case 'Result ⇒ Int.MaxValue
  }: Any ⇒ Int), 1000, 10 seconds)

}

class PriorityDispatcherSpec extends AkkaSpec(PriorityDispatcherSpec.config) with DefaultTimeout {

      @Test def `must Order it's messages according to the specified comparator using an unbounded mailbox`: Unit = {
      val dispatcherKey = "unbounded-prio-dispatcher"
      testOrdering(dispatcherKey)
    }

    @Test def `must Order it's messages according to the specified comparator using a bounded mailbox`: Unit = {
      val dispatcherKey = "bounded-prio-dispatcher"
      testOrdering(dispatcherKey)
    }
  }

  def testOrdering(dispatcherKey: String) {
    val msgs = (1 to 100) toList

    // It's important that the actor under test is not a top level actor
    // with RepointableActorRef, since messages might be queued in
    // UnstartedCell and the sent to the PriorityQueue and consumed immediately
    // without the ordering taking place.
    val actor = system.actorOf(Props(new Actor {
      context.actorOf(Props(new Actor {

        val acc = scala.collection.mutable.ListBuffer[Int]()

        scala.util.Random.shuffle(msgs) foreach { m ⇒ self ! m }

        self.tell('Result, testActor)

        def receive = {
          case i: Int  ⇒ acc += i
          case 'Result ⇒ sender ! acc.toList
        }
      }).withDispatcher(dispatcherKey))

      def receive = Actor.emptyBehavior

    }))

    assertThat(expectMsgType[List[_]], equalTo(msgs))
  }