/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.testkit.{ AkkaSpec, EventFilter }
import akka.actor.ActorDSL._
import akka.event.Logging.Warning
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import java.util.concurrent.TimeoutException

class ActorDSLDummy {
  //#import
  import akka.actor.ActorDSL._
  import akka.actor.ActorSystem

  implicit val system = ActorSystem("demo")
  //#import
}

class ActorDSLSpec extends AkkaSpec {

  val echo = system.actorOf(Props(new Actor {
    def receive = {
      case x ⇒ sender ! x
    }
  }))

  
    @Test def `must function as implicit sender`: Unit = {
      //#inbox
      implicit val i = inbox()
      echo ! "hello"
      assertThat(i.receive(), equalTo("hello"))
      //#inbox
    }

    @Test def `must support watch`: Unit = {
      //#watch
      val target = // some actor
        //#watch
        actor(new Act {})
      //#watch
      val i = inbox()
      i watch target
      //#watch
      target ! PoisonPill
      assertThat(i receive 1.second, equalTo(Terminated(target)(true, false)))
    }

    @Test def `must support queueing multiple queries`: Unit = {
      val i = inbox()
      import system.dispatcher
      val res = Future.sequence(Seq(
        Future { i.receive() } recover { case x ⇒ x },
        Future { Thread.sleep(100); i.select() { case "world" ⇒ 1 } } recover { case x ⇒ x },
        Future { Thread.sleep(200); i.select() { case "hello" ⇒ 2 } } recover { case x ⇒ x }))
      Thread.sleep(1000)
      assertThat(res.isCompleted, equalTo(false))
      i.receiver ! 42
      i.receiver ! "hello"
      i.receiver ! "world"
      assertThat(Await.result(res, 5 second), equalTo(Seq(42, 1, 2)))
    }

    @Test def `must support selective receives`: Unit = {
      val i = inbox()
      i.receiver ! "hello"
      i.receiver ! "world"
      val result = i.select() {
        case "world" ⇒ true
      }
      assertThat(result, equalTo(true))
      assertThat(i.receive(), equalTo("hello"))
    }

    @Test def `must have a maximum queue size`: Unit = {
      val i = inbox()
      system.eventStream.subscribe(testActor, classOf[Warning])
      try {
        for (_ ← 1 to 1000) i.receiver ! 0
        expectNoMsg(1 second)
        EventFilter.warning(start = "dropping message", occurrences = 1) intercept {
          i.receiver ! 42
        }
        expectMsgType[Warning]
        i.receiver ! 42
        expectNoMsg(1 second)
        val gotit = for (_ ← 1 to 1000) yield i.receive()
        assertThat(gotit, equalTo((1 to 1000) map (_ ⇒ 0)))
        intercept[TimeoutException] {
          i.receive(1 second)
        }
      } finally {
        system.eventStream.unsubscribe(testActor, classOf[Warning])
      }
    }

    @Test def `must have a default and custom timeouts`: Unit = {
      val i = inbox()
      within(5 seconds, 6 seconds) {
        intercept[TimeoutException](i.receive())
      }
      within(1 second) {
        intercept[TimeoutException](i.receive(100 millis))
      }
    }

  }

  
    @Test def `must support creating regular actors`: Unit = {
      //#simple-actor
      val a = actor(new Act {
        become {
          case "hello" ⇒ sender ! "hi"
        }
      })
      //#simple-actor

      implicit val i = inbox()
      a ! "hello"
      assertThat(i.receive(), equalTo("hi"))
    }

    @Test def `must support becomeStacked`: Unit = {
      //#becomeStacked
      val a = actor(new Act {
        become { // this will replace the initial (empty) behavior
          case "info" ⇒ sender ! "A"
          case "switch" ⇒
            becomeStacked { // this will stack upon the "A" behavior
              case "info"   ⇒ sender ! "B"
              case "switch" ⇒ unbecome() // return to the "A" behavior
            }
          case "lobotomize" ⇒ unbecome() // OH NOES: Actor.emptyBehavior
        }
      })
      //#becomeStacked

      implicit def sender = testActor
      a ! "info"
      expectMsg("A")
      a ! "switch"
      a ! "info"
      expectMsg("B")
      a ! "switch"
      a ! "info"
      expectMsg("A")
    }

    @Test def `must support setup/teardown`: Unit = {
      //#simple-start-stop
      val a = actor(new Act {
        whenStarting { testActor ! "started" }
        whenStopping { testActor ! "stopped" }
      })
      //#simple-start-stop

      system stop a
      expectMsg("started")
      expectMsg("stopped")
    }

    @Test def `must support restart`: Unit = {
      //#failing-actor
      val a = actor(new Act {
        become {
          case "die" ⇒ throw new Exception
        }
        whenFailing { case m @ (cause, msg) ⇒ testActor ! m }
        whenRestarted { cause ⇒ testActor ! cause }
      })
      //#failing-actor

      EventFilter[Exception](occurrences = 1) intercept {
        a ! "die"
      }
      expectMsgPF() { case (x: Exception, Some("die")) ⇒ }
      expectMsgPF() { case _: Exception ⇒ }
    }

    @Test def `must support superviseWith`: Unit = {
      val a = actor(new Act {
        val system = null // shadow the implicit system
        //#supervise-with
        superviseWith(OneForOneStrategy() {
          case e: Exception if e.getMessage == "hello" ⇒ Stop
          case _: Exception                            ⇒ Resume
        })
        //#supervise-with
        val child = actor("child")(new Act {
          whenFailing { (_, _) ⇒ }
          become {
            case ref: ActorRef ⇒ whenStopping(ref ! "stopped")
            case ex: Exception ⇒ throw ex
          }
        })
        become {
          case x ⇒ child ! x
        }
      })
      a ! testActor
      EventFilter.warning("hi", occurrences = 1) intercept {
        a ! new Exception("hi")
      }
      expectNoMsg(1 second)
      EventFilter[Exception]("hello", occurrences = 1) intercept {
        a ! new Exception("hello")
      }
      expectMsg("stopped")
    }

    @Test def `must supported nested declaration`: Unit = {
      val system = this.system
      //#nested-actor
      // here we pass in the ActorRefFactory explicitly as an example
      val a = actor(system, "fred")(new Act {
        val b = actor("barney")(new Act {
          whenStarting { context.parent ! ("hello from " + self.path) }
        })
        become {
          case x ⇒ testActor ! x
        }
      })
      //#nested-actor
      expectMsg("hello from akka://ActorDSLSpec/user/fred/barney")
      assertThat(lastSender, equalTo(a))
    }

    @Test def `must support Stash`: Unit = {
      //#act-with-stash
      val a = actor(new ActWithStash {
        become {
          case 1 ⇒ stash()
          case 2 ⇒
            testActor ! 2; unstashAll(); becomeStacked {
              case 1 ⇒ testActor ! 1; unbecome()
            }
        }
      })
      //#act-with-stash

      a ! 1
      a ! 2
      expectMsg(2)
      expectMsg(1)
      a ! 1
      a ! 2
      expectMsg(2)
      expectMsg(1)
    }

  }