/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import com.typesafe.config.ConfigFactory
import akka.testkit._
import akka.dispatch._
import akka.TestUtils.verifyActorTermination
import scala.concurrent.duration.Duration
import akka.ConfigurationException
import com.typesafe.config.Config

object ActorMailboxSpec {
  val mailboxConf = ConfigFactory.parseString("""
    unbounded-dispatcher {
      mailbox-type = "akka.dispatch.UnboundedMailbox"
    }

    bounded-dispatcher {
      mailbox-capacity = 1000
      mailbox-push-timeout-time = 10s
      mailbox-type = "akka.dispatch.BoundedMailbox"
    }

    requiring-bounded-dispatcher {
      mailbox-capacity = 1000
      mailbox-push-timeout-time = 10s
      mailbox-type = "akka.dispatch.BoundedMailbox"
      mailbox-requirement = "akka.dispatch.BoundedMessageQueueSemantics"
    }

    unbounded-mailbox {
      mailbox-type = "akka.dispatch.UnboundedMailbox"
    }

    bounded-mailbox {
      mailbox-capacity = 1000
      mailbox-push-timeout-time = 10s
      mailbox-type = "akka.dispatch.BoundedMailbox"
    }

    bounded-mailbox-with-zero-pushtimeout {
      mailbox-capacity = 1000
      mailbox-push-timeout-time = 0s
      mailbox-type = "akka.dispatch.BoundedMailbox"
    }

    akka.actor.deployment {
      /default-default {
      }
      /default-override-from-props {
      }
      /default-override-from-trait {
      }
      /default-override-from-stash {
      }
      /default-bounded {
        mailbox = bounded-mailbox
      }
      /default-bounded-mailbox-with-zero-pushtimeout {
        mailbox = bounded-mailbox-with-zero-pushtimeout
      }
      /default-unbounded-deque {
        mailbox = akka.actor.mailbox.unbounded-deque-based
      }
      /default-unbounded-deque-override-trait {
        mailbox = akka.actor.mailbox.unbounded-deque-based
      }
      /unbounded-default {
        dispatcher = unbounded-dispatcher
      }
      /unbounded-default-override-trait {
        dispatcher = unbounded-dispatcher
      }
      /unbounded-bounded {
        dispatcher= unbounded-dispatcher
        mailbox = bounded-mailbox
      }
      /bounded-default {
        dispatcher = bounded-dispatcher
      }
      /bounded-unbounded {
        dispatcher = bounded-dispatcher
        mailbox = unbounded-mailbox
      }
      /bounded-unbounded-override-props {
        dispatcher = bounded-dispatcher
        mailbox = unbounded-mailbox
      }
      /bounded-deque-requirements-configured {
        dispatcher = requiring-bounded-dispatcher
        mailbox = akka.actor.mailbox.bounded-deque-based
      }
      /bounded-deque-require-unbounded-configured {
        dispatcher = requiring-bounded-dispatcher
        mailbox = akka.actor.mailbox.unbounded-deque-based
      }
      /bounded-deque-require-unbounded-unconfigured {
        dispatcher = requiring-bounded-dispatcher
      }
      /bounded-deque-requirements-configured-props-disp {
        mailbox = akka.actor.mailbox.bounded-deque-based
      }
      /bounded-deque-require-unbounded-configured-props-disp {
        mailbox = akka.actor.mailbox.unbounded-deque-based
      }
      /bounded-deque-requirements-configured-props-mail {
        dispatcher = requiring-bounded-dispatcher
      }
      /bounded-deque-require-unbounded-configured-props-mail {
        dispatcher = requiring-bounded-dispatcher
      }
      /bounded-deque-require-unbounded-unconfigured-props-mail {
        dispatcher = requiring-bounded-dispatcher
      }
    }
  """)

  class QueueReportingActor extends Actor {
    def receive = {
      case _ ⇒ sender ! context.asInstanceOf[ActorCell].mailbox.messageQueue
    }
  }

  class BoundedQueueReportingActor extends QueueReportingActor with RequiresMessageQueue[BoundedMessageQueueSemantics]

  class StashQueueReportingActor extends QueueReportingActor with Stash

  val UnboundedMailboxTypes = Seq(classOf[UnboundedMessageQueueSemantics])
  val BoundedMailboxTypes = Seq(classOf[BoundedMessageQueueSemantics])
  val UnboundedDeqMailboxTypes = Seq(
    classOf[DequeBasedMessageQueueSemantics],
    classOf[UnboundedMessageQueueSemantics],
    classOf[UnboundedDequeBasedMessageQueueSemantics])
  val BoundedDeqMailboxTypes = Seq(
    classOf[DequeBasedMessageQueueSemantics],
    classOf[BoundedMessageQueueSemantics],
    classOf[BoundedDequeBasedMessageQueueSemantics])
}

class ActorMailboxSpec(conf: Config) extends AkkaSpec(conf) with DefaultTimeout with ImplicitSender {

  import ActorMailboxSpec._

  def this() = this(ActorMailboxSpec.mailboxConf)

  def checkMailboxQueue(props: Props, name: String, types: Seq[Class[_]]): MessageQueue = {
    val actor = system.actorOf(props, name)

    actor ! "ping"
    val q = expectMsgType[MessageQueue]
    types foreach (t ⇒ assert(t isInstance q, s"Type [${q.getClass.getName}] is not assignable to [${t.getName}]"))
    q
  }

  
    @Test def `must get an unbounded message queue by default`: Unit = {
      checkMailboxQueue(Props[QueueReportingActor], "default-default", UnboundedMailboxTypes)
    }

    @Test def `must get an unbounded deque message queue when it is only configured on the props`: Unit = {
      checkMailboxQueue(Props[QueueReportingActor].withMailbox("akka.actor.mailbox.unbounded-deque-based"),
        "default-override-from-props", UnboundedDeqMailboxTypes)
    }

    @Test def `must get an bounded message queue when it's only configured with RequiresMailbox`: Unit = {
      checkMailboxQueue(Props[BoundedQueueReportingActor],
        "default-override-from-trait", BoundedMailboxTypes)
    }

    @Test def `must get an unbounded deque message queue when it's only mixed with Stash`: Unit = {
      checkMailboxQueue(Props[StashQueueReportingActor],
        "default-override-from-stash", UnboundedDeqMailboxTypes)
    }

    @Test def `must get a bounded message queue when it's configured as mailbox`: Unit = {
      checkMailboxQueue(Props[QueueReportingActor], "default-bounded", BoundedMailboxTypes)
    }

    @Test def `must get an unbounded deque message queue when it's configured as mailbox`: Unit = {
      checkMailboxQueue(Props[QueueReportingActor], "default-unbounded-deque", UnboundedDeqMailboxTypes)
    }

    @Test def `must fail to create actor when an unbounded dequeu message queue is configured as mailbox overriding RequestMailbox`: Unit = {
      intercept[ConfigurationException](system.actorOf(Props[BoundedQueueReportingActor], "default-unbounded-deque-override-trait"))
    }

    @Test def `must get an unbounded message queue when defined in dispatcher`: Unit = {
      checkMailboxQueue(Props[QueueReportingActor], "unbounded-default", UnboundedMailboxTypes)
    }

    @Test def `must fail to create actor when an unbounded message queue is defined in dispatcher overriding RequestMailbox`: Unit = {
      intercept[ConfigurationException](system.actorOf(Props[BoundedQueueReportingActor], "unbounded-default-override-trait"))
    }

    @Test def `must get a bounded message queue when it's configured as mailbox overriding unbounded in dispatcher`: Unit = {
      checkMailboxQueue(Props[QueueReportingActor], "unbounded-bounded", BoundedMailboxTypes)
    }

    @Test def `must get a bounded message queue when defined in dispatcher`: Unit = {
      checkMailboxQueue(Props[QueueReportingActor], "bounded-default", BoundedMailboxTypes)
    }

    @Test def `must get a bounded message queue with 0 push timeout when defined in dispatcher`: Unit = {
      val q = checkMailboxQueue(Props[QueueReportingActor], "default-bounded-mailbox-with-zero-pushtimeout", BoundedMailboxTypes)
      assertThat(q.asInstanceOf[BoundedMessageQueueSemantics].pushTimeOut, equalTo(Duration.Zero))
    }

    @Test def `must get an unbounded message queue when it's configured as mailbox overriding bounded in dispatcher`: Unit = {
      checkMailboxQueue(Props[QueueReportingActor], "bounded-unbounded", UnboundedMailboxTypes)
    }

    @Test def `must get an unbounded message queue overriding configuration on the props`: Unit = {
      checkMailboxQueue(Props[QueueReportingActor].withMailbox("akka.actor.mailbox.unbounded-deque-based"),
        "bounded-unbounded-override-props", UnboundedMailboxTypes)
    }

    @Test def `must get a bounded deque-based message queue if configured and required`: Unit = {
      checkMailboxQueue(Props[StashQueueReportingActor], "bounded-deque-requirements-configured", BoundedDeqMailboxTypes)
    }

    @Test def `must fail with a unbounded deque-based message queue if configured and required`: Unit = {
      intercept[ConfigurationException](system.actorOf(Props[StashQueueReportingActor], "bounded-deque-require-unbounded-configured"))
    }

    @Test def `must fail with a bounded deque-based message queue if not configured`: Unit = {
      intercept[ConfigurationException](system.actorOf(Props[StashQueueReportingActor], "bounded-deque-require-unbounded-unconfigured"))
    }

    @Test def `must get a bounded deque-based message queue if configured and required with Props`: Unit = {
      checkMailboxQueue(
        Props[StashQueueReportingActor]
          .withDispatcher("requiring-bounded-dispatcher")
          .withMailbox("akka.actor.mailbox.bounded-deque-based"),
        "bounded-deque-requirements-configured-props",
        BoundedDeqMailboxTypes)
    }

    @Test def `must fail with a unbounded deque-based message queue if configured and required with Props`: Unit = {
      intercept[ConfigurationException](system.actorOf(
        Props[StashQueueReportingActor]
          .withDispatcher("requiring-bounded-dispatcher")
          .withMailbox("akka.actor.mailbox.unbounded-deque-based"),
        "bounded-deque-require-unbounded-configured-props"))
    }

    @Test def `must fail with a bounded deque-based message queue if not configured with Props`: Unit = {
      intercept[ConfigurationException](system.actorOf(
        Props[StashQueueReportingActor]
          .withDispatcher("requiring-bounded-dispatcher"),
        "bounded-deque-require-unbounded-unconfigured-props"))
    }

    @Test def `must get a bounded deque-based message queue if configured and required with Props (dispatcher)`: Unit = {
      checkMailboxQueue(
        Props[StashQueueReportingActor]
          .withDispatcher("requiring-bounded-dispatcher"),
        "bounded-deque-requirements-configured-props-disp",
        BoundedDeqMailboxTypes)
    }

    @Test def `must fail with a unbounded deque-based message queue if configured and required with Props (dispatcher)`: Unit = {
      intercept[ConfigurationException](system.actorOf(
        Props[StashQueueReportingActor]
          .withDispatcher("requiring-bounded-dispatcher"),
        "bounded-deque-require-unbounded-configured-props-disp"))
    }

    @Test def `must fail with a bounded deque-based message queue if not configured with Props (dispatcher)`: Unit = {
      intercept[ConfigurationException](system.actorOf(
        Props[StashQueueReportingActor]
          .withDispatcher("requiring-bounded-dispatcher"),
        "bounded-deque-require-unbounded-unconfigured-props-disp"))
    }

    @Test def `must get a bounded deque-based message queue if configured and required with Props (mailbox)`: Unit = {
      checkMailboxQueue(
        Props[StashQueueReportingActor]
          .withMailbox("akka.actor.mailbox.bounded-deque-based"),
        "bounded-deque-requirements-configured-props-mail",
        BoundedDeqMailboxTypes)
    }

    @Test def `must fail with a unbounded deque-based message queue if configured and required with Props (mailbox)`: Unit = {
      intercept[ConfigurationException](system.actorOf(
        Props[StashQueueReportingActor]
          .withMailbox("akka.actor.mailbox.unbounded-deque-based"),
        "bounded-deque-require-unbounded-configured-props-mail"))
    }

    @Test def `must fail with a bounded deque-based message queue if not configured with Props (mailbox)`: Unit = {
      intercept[ConfigurationException](system.actorOf(
        Props[StashQueueReportingActor],
        "bounded-deque-require-unbounded-unconfigured-props-mail"))
    }

  }