/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.Actor._
import akka.testkit.{ TestKit, EventFilter, filterEvents, filterException, AkkaSpec, ImplicitSender, DefaultTimeout }
import akka.dispatch.Dispatchers
import akka.pattern.ask

class SupervisorTreeSpec extends AkkaSpec with ImplicitSender with DefaultTimeout {

  
    @Test def `must be able to kill the middle actor and see itself and its child restarted`: Unit = {
      EventFilter[ActorKilledException](occurrences = 1) intercept {
        within(5 seconds) {
          val p = Props(new Actor {
            override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 second)(List(classOf[Exception]))
            def receive = {
              case p: Props ⇒ sender ! context.actorOf(p)
            }
            override def preRestart(cause: Throwable, msg: Option[Any]) { testActor ! self.path }
          })
          val headActor = system.actorOf(p)
          val middleActor = Await.result((headActor ? p).mapTo[ActorRef], timeout.duration)
          val lastActor = Await.result((middleActor ? p).mapTo[ActorRef], timeout.duration)

          middleActor ! Kill
          expectMsg(middleActor.path)
          expectMsg(lastActor.path)
          expectNoMsg(2 seconds)
          system.stop(headActor)
        }
      }
    }
  }