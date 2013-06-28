/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps
import akka.testkit._
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.util.Timeout
import scala.concurrent.Future
import scala.util.Success
import scala.util.Failure

object LocalActorRefProviderSpec {
  val config = """
    akka {
      actor {
        default-dispatcher {
          executor = "thread-pool-executor"
          thread-pool-executor {
            core-pool-size-min = 16
            core-pool-size-max = 16
          }
        }
      }
    }
  """
}

class LocalActorRefProviderSpec extends AkkaSpec(LocalActorRefProviderSpec.config) {
  
    @Test def `must find actor refs using actorFor`: Unit = {
      val a = system.actorOf(Props(new Actor { def receive = { case _ ⇒ } }))
      val b = system.actorFor(a.path)
      assertThat(a, equalTo(b))
    }

    @Test def `must find child actor with URL encoded name using actorFor`: Unit = {
      val childName = "akka%3A%2F%2FClusterSystem%40127.0.0.1%3A2552"
      val a = system.actorOf(Props(new Actor {
        val child = context.actorOf(Props.empty, name = childName)
        def receive = {
          case "lookup" ⇒
            if (childName == child.path.name) sender ! context.actorFor(childName)
            else sender ! s"$childName is not ${child.path.name}!"
        }
      }))
      a.tell("lookup", testActor)
      val b = expectMsgType[ActorRef]
      assertThat(b.isTerminated, equalTo(false))
      assertThat(b.path.name, equalTo(childName))
    }

  }

      @Test def `must not retain its original Props when terminated`: Unit = {
      val GetChild = "GetChild"
      val a = watch(system.actorOf(Props(new Actor {
        val child = context.actorOf(Props.empty)
        def receive = { case `GetChild` ⇒ sender ! child }
      })))
      a.tell(GetChild, testActor)
      val child = expectMsgType[ActorRef]
      val childProps1 = child.asInstanceOf[LocalActorRef].underlying.props
      assertThat(childProps1, equalTo(Props.empty))
      system stop a
      expectTerminated(a)
      // the fields are cleared after the Terminated message has been sent,
      // so we need to check for a reasonable time after we receive it
      awaitAssert({
        val childProps2 = child.asInstanceOf[LocalActorRef].underlying.props
        assertThat(childProps2, not(sameInstance(childProps1)))
        assertThat(childProps2, sameInstance(ActorCell.terminatedProps))
      }, 1 second)
    }
  }

      implicit val ec = system.dispatcher
    @Test def `must only create one instance of an actor with a specific address in a concurrent environment`: Unit = {
      val impl = system.asInstanceOf[ActorSystemImpl]
      val provider = impl.provider

      assertThat(provider.isInstanceOf[LocalActorRefProvider], equalTo(true))

      for (i ← 0 until 100) {
        val address = "new-actor" + i
        implicit val timeout = Timeout(5 seconds)
        val actors = for (j ← 1 to 4) yield Future(system.actorOf(Props(new Actor { def receive = { case _ ⇒ } }), address))
        val set = Set() ++ actors.map(a ⇒ Await.ready(a, timeout.duration).value match {
          case Some(Success(a: ActorRef)) ⇒ 1
          case Some(Failure(ex: InvalidActorNameException)) ⇒ 2
          case x ⇒ x
        })
        assertThat(set, equalTo(Set(1, 2)))
      }
    }

    @Test def `must only create one instance of an actor from within the same message invocation`: Unit = {
      val supervisor = system.actorOf(Props(new Actor {
        def receive = {
          case "" ⇒
            val a, b = context.actorOf(Props.empty, "duplicate")
        }
      }))
      EventFilter[InvalidActorNameException](occurrences = 1) intercept {
        supervisor ! ""
      }
    }

    @Test def `must throw suitable exceptions for malformed actor names`: Unit = {
      assertThat(intercept[InvalidActorNameException](system.actorOf(Props.empty, null)).getMessage.contains("null"), equalTo(true))
      assertThat(intercept[InvalidActorNameException](system.actorOf(Props.empty, "")).getMessage.contains("empty"), equalTo(true))
      assertThat(intercept[InvalidActorNameException](system.actorOf(Props.empty, "$hallo")).getMessage.contains("conform"), equalTo(true))
      assertThat(intercept[InvalidActorNameException](system.actorOf(Props.empty, "a%")).getMessage.contains("conform"), equalTo(true))
      assertThat(intercept[InvalidActorNameException](system.actorOf(Props.empty, "%3")).getMessage.contains("conform"), equalTo(true))
      assertThat(intercept[InvalidActorNameException](system.actorOf(Props.empty, "%1t")).getMessage.contains("conform"), equalTo(true))
      assertThat(intercept[InvalidActorNameException](system.actorOf(Props.empty, "a?")).getMessage.contains("conform"), equalTo(true))
      assertThat(intercept[InvalidActorNameException](system.actorOf(Props.empty, "üß")).getMessage.contains("conform"), equalTo(true))
    }

  }