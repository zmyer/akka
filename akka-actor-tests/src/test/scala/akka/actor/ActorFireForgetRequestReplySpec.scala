/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import akka.testkit._
import scala.concurrent.duration._
import scala.concurrent.Await
import akka.pattern.ask

object ActorFireForgetRequestReplySpec {

  class ReplyActor extends Actor {
    def receive = {
      case "Send" ⇒
        sender ! "Reply"
      case "SendImplicit" ⇒
        sender ! "ReplyImplicit"
    }
  }

  class CrashingActor extends Actor {
    import context.system
    def receive = {
      case "Die" ⇒
        state.finished.await
        throw new Exception("Expected exception")
    }
  }

  class SenderActor(replyActor: ActorRef) extends Actor {
    import context.system
    def receive = {
      case "Init" ⇒
        replyActor ! "Send"
      case "Reply" ⇒ {
        state.s = "Reply"
        state.finished.await
      }
      case "InitImplicit" ⇒ replyActor ! "SendImplicit"
      case "ReplyImplicit" ⇒ {
        state.s = "ReplyImplicit"
        state.finished.await
      }
    }
  }

  object state {
    var s = "NIL"
    val finished = TestBarrier(2)
  }
}

class ActorFireForgetRequestReplySpec extends AkkaSpec with BeforeAndAfterEach with DefaultTimeout {
  import ActorFireForgetRequestReplySpec._

  override def beforeEach() = {
    state.finished.reset
  }

  
    @Test def `must reply to bang message using reply`: Unit = {
      val replyActor = system.actorOf(Props[ReplyActor])
      val senderActor = system.actorOf(Props(new SenderActor(replyActor)))
      senderActor ! "Init"
      state.finished.await
      assertThat(state.s, equalTo("Reply"))
    }

    @Test def `must reply to bang message using implicit sender`: Unit = {
      val replyActor = system.actorOf(Props[ReplyActor])
      val senderActor = system.actorOf(Props(new SenderActor(replyActor)))
      senderActor ! "InitImplicit"
      state.finished.await
      assertThat(state.s, equalTo("ReplyImplicit"))
    }

    @Test def `must shutdown crashed temporary actor`: Unit = {
      filterEvents(EventFilter[Exception]("Expected exception")) {
        val supervisor = system.actorOf(Props(new Supervisor(
          OneForOneStrategy(maxNrOfRetries = 0)(List(classOf[Exception])))))
        val actor = Await.result((supervisor ? Props[CrashingActor]).mapTo[ActorRef], timeout.duration)
        assertThat(actor.isTerminated, equalTo(false))
        actor ! "Die"
        state.finished.await
        Thread.sleep(1.second.dilated.toMillis)
        assertThat(actor.isTerminated, equalTo(true))
        system.stop(supervisor)
      }
    }
  }