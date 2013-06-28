/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.testkit._
import akka.testkit.DefaultTimeout
import akka.testkit.TestEvent._
import scala.concurrent.duration._
import akka.routing._
import akka.ConfigurationException

object ActorConfigurationVerificationSpec {

  class TestActor extends Actor {
    def receive: Receive = {
      case _ ⇒
    }
  }

  val config = """
    balancing-dispatcher {
      type = BalancingDispatcher
      throughput = 1
    }
    pinned-dispatcher {
      executor = "thread-pool-executor"
      type = PinnedDispatcher
    }
    """
}

class ActorConfigurationVerificationSpec extends AkkaSpec(ActorConfigurationVerificationSpec.config) with DefaultTimeout with BeforeAndAfterEach {
  import ActorConfigurationVerificationSpec._

  override def atStartup {
    system.eventStream.publish(Mute(EventFilter[ConfigurationException]("")))
  }

      @Test def `must fail verification with a ConfigurationException if also configured with a RoundRobinRouter`: Unit = {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(2).withDispatcher("balancing-dispatcher")))
      }
    }
    @Test def `must fail verification with a ConfigurationException if also configured with a BroadcastRouter`: Unit = {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withRouter(BroadcastRouter(2).withDispatcher("balancing-dispatcher")))
      }
    }
    @Test def `must fail verification with a ConfigurationException if also configured with a RandomRouter`: Unit = {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withRouter(RandomRouter(2).withDispatcher("balancing-dispatcher")))
      }
    }
    @Test def `must fail verification with a ConfigurationException if also configured with a SmallestMailboxRouter`: Unit = {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withRouter(SmallestMailboxRouter(2).withDispatcher("balancing-dispatcher")))
      }
    }
    @Test def `must fail verification with a ConfigurationException if also configured with a ScatterGatherFirstCompletedRouter`: Unit = {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withRouter(ScatterGatherFirstCompletedRouter(nrOfInstances = 2, within = 2 seconds).withDispatcher("balancing-dispatcher")))
      }
    }
    @Test def `must not fail verification with a ConfigurationException also not configured with a Router`: Unit = {
      system.actorOf(Props[TestActor].withDispatcher("balancing-dispatcher"))
    }
  }
      @Test def `must not fail verification with a ConfigurationException if also configured with a Router`: Unit = {
      system.actorOf(Props[TestActor].withDispatcher("pinned-dispatcher").withRouter(RoundRobinRouter(2)))
    }

    @Test def `must fail verification if the dispatcher cannot be found`: Unit = {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withDispatcher("does not exist"))
      }
    }

    @Test def `must fail verification if the dispatcher cannot be found for the head of a router`: Unit = {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(1, routerDispatcher = "does not exist")))
      }
    }

    @Test def `must fail verification if the dispatcher cannot be found for the routees of a router`: Unit = {
      intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withDispatcher("does not exist").withRouter(RoundRobinRouter(1)))
      }
    }
  }