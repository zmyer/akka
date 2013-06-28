/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.actor._
import scala.collection.immutable
import akka.testkit._
import scala.concurrent.duration._
import scala.concurrent.Await
import akka.ConfigurationException
import com.typesafe.config.ConfigFactory
import akka.pattern.{ ask, pipe }
import java.util.concurrent.ConcurrentHashMap
import com.typesafe.config.Config
import akka.dispatch.Dispatchers
import akka.util.Collections.EmptyImmutableSeq
import akka.util.Timeout
import java.util.concurrent.atomic.AtomicInteger

object RoutingSpec {

  val config = """
    akka.actor.deployment {
      /router1 {
        router = round-robin
        nr-of-instances = 3
      }
      /router2 {
        router = round-robin
        nr-of-instances = 3
      }
      /router3 {
        router = round-robin
      }
      /myrouter {
        router = "akka.routing.RoutingSpec$MyRouter"
        foo = bar
      }
    }
    """

  class TestActor extends Actor {
    def receive = { case _ ⇒ }
  }

  class Echo extends Actor {
    def receive = {
      case _ ⇒ sender ! self
    }
  }

  class MyRouter(config: Config) extends RouterConfig {
    val foo = config.getString("foo")
    def createRoute(routeeProvider: RouteeProvider): Route = {
      routeeProvider.registerRoutees(List(routeeProvider.context.actorOf(Props[Echo])))

      {
        case (sender, message) ⇒ EmptyImmutableSeq
      }
    }
    def routerDispatcher: String = Dispatchers.DefaultDispatcherId
    def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.defaultStrategy
  }

}

class RoutingSpec extends AkkaSpec(RoutingSpec.config) with DefaultTimeout with ImplicitSender {
  implicit val ec = system.dispatcher
  import akka.routing.RoutingSpec._

  muteDeadLetters(classOf[akka.dispatch.sysmsg.DeathWatchNotification])()

  
    @Test def `must evict terminated routees`: Unit = {
      val router = system.actorOf(Props[Echo].withRouter(RoundRobinRouter(2)))
      router ! ""
      router ! ""
      val c1, c2 = expectMsgType[ActorRef]
      watch(router)
      watch(c2)
      system.stop(c2)
      assertThat(expectTerminated(c2).existenceConfirmed, equalTo(true))
      // it might take a while until the Router has actually processed the Terminated message
      awaitCond {
        router ! ""
        router ! ""
        val res = receiveWhile(100 millis, messages = 2) {
          case x: ActorRef ⇒ x
        }
        res == Seq(c1, c1)
      }
      system.stop(c1)
      assertThat(expectTerminated(router).existenceConfirmed, equalTo(true))
    }

    @Test def `must not terminate when resizer is used`: Unit = {
      val latch = TestLatch(1)
      val resizer = new Resizer {
        def isTimeForResize(messageCounter: Long): Boolean = messageCounter == 0
        def resize(routeeProvider: RouteeProvider): Unit = {
          routeeProvider.createRoutees(nrOfInstances = 2)
          latch.countDown()
        }
      }
      val router = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(resizer = Some(resizer))))
      watch(router)
      Await.ready(latch, remaining)
      router ! CurrentRoutees
      val routees = expectMsgType[RouterRoutees].routees
      assertThat(routees.size, equalTo(2))
      routees foreach system.stop
      // expect no Terminated
      expectNoMsg(2.seconds)
    }

    @Test def `must be able to send their routees`: Unit = {
      case class TestRun(id: String, names: immutable.Iterable[String], actors: Int)
      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case TestRun(id, names, actors) ⇒
            val routerProps = Props[TestActor].withRouter(
              ScatterGatherFirstCompletedRouter(
                routees = names map { context.actorOf(Props(new TestActor), _) },
                within = 5 seconds))

            1 to actors foreach { i ⇒ context.actorOf(routerProps, id + i).tell(CurrentRoutees, testActor) }
        }
      }))

      val actors = 15
      val names = 1 to 20 map { "routee" + _ } toList

      actor ! TestRun("test", names, actors)

      1 to actors foreach { _ ⇒
        val routees = expectMsgType[RouterRoutees].routees
        assertThat(routees.map(_.path.name), equalTo(names))
      }
      expectNoMsg(500.millis)
    }

    @Test def `must use configured nr-of-instances when FromConfig`: Unit = {
      val router = system.actorOf(Props[TestActor].withRouter(FromConfig), "router1")
      router ! CurrentRoutees
      assertThat(expectMsgType[RouterRoutees].routees.size, equalTo(3))
      watch(router)
      system.stop(router)
      expectTerminated(router)
    }

    @Test def `must use configured nr-of-instances when router is specified`: Unit = {
      val router = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(nrOfInstances = 2)), "router2")
      router ! CurrentRoutees
      assertThat(expectMsgType[RouterRoutees].routees.size, equalTo(3))
      system.stop(router)
    }

    @Test def `must use specified resizer when resizer not configured`: Unit = {
      val latch = TestLatch(1)
      val resizer = new Resizer {
        def isTimeForResize(messageCounter: Long): Boolean = messageCounter == 0
        def resize(routeeProvider: RouteeProvider): Unit = {
          routeeProvider.createRoutees(nrOfInstances = 3)
          latch.countDown()
        }
      }
      val router = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(resizer = Some(resizer))), "router3")
      Await.ready(latch, remaining)
      router ! CurrentRoutees
      assertThat(expectMsgType[RouterRoutees].routees.size, equalTo(3))
      system.stop(router)
    }

    @Test def `must set supplied supervisorStrategy`: Unit = {
      //#supervision
      val escalator = OneForOneStrategy() {
        //#custom-strategy
        case e ⇒ testActor ! e; SupervisorStrategy.Escalate
        //#custom-strategy
      }
      val router = system.actorOf(Props.empty.withRouter(
        RoundRobinRouter(1, supervisorStrategy = escalator)))
      //#supervision
      router ! CurrentRoutees
      EventFilter[ActorKilledException](occurrences = 1) intercept {
        expectMsgType[RouterRoutees].routees.head ! Kill
      }
      expectMsgType[ActorKilledException]

      val router2 = system.actorOf(Props.empty.withRouter(RoundRobinRouter(1).withSupervisorStrategy(escalator)))
      router2 ! CurrentRoutees
      EventFilter[ActorKilledException](occurrences = 1) intercept {
        expectMsgType[RouterRoutees].routees.head ! Kill
      }
      expectMsgType[ActorKilledException]
    }

    @Test def `must set supplied supervisorStrategy for FromConfig`: Unit = {
      val escalator = OneForOneStrategy() {
        case e ⇒ testActor ! e; SupervisorStrategy.Escalate
      }
      val router = system.actorOf(Props.empty.withRouter(FromConfig.withSupervisorStrategy(escalator)), "router1")
      router ! CurrentRoutees
      EventFilter[ActorKilledException](occurrences = 1) intercept {
        expectMsgType[RouterRoutees].routees.head ! Kill
      }
      expectMsgType[ActorKilledException]
    }

    @Test def `must default to all-for-one-always-escalate strategy`: Unit = {
      val restarter = OneForOneStrategy() {
        case e ⇒ testActor ! e; SupervisorStrategy.Restart
      }
      val supervisor = system.actorOf(Props(new Supervisor(restarter)))
      supervisor ! Props(new Actor {
        def receive = {
          case x: String ⇒ throw new Exception(x)
        }
        override def postRestart(reason: Throwable): Unit = testActor ! "restarted"
      }).withRouter(RoundRobinRouter(3))
      val router = expectMsgType[ActorRef]
      EventFilter[Exception]("die", occurrences = 1) intercept {
        router ! "die"
      }
      assertThat(expectMsgType[Exception].getMessage, equalTo("die"))
      expectMsg("restarted")
      expectMsg("restarted")
      expectMsg("restarted")
    }

    @Test def `must start in-line for context.actorOf()`: Unit = {
      system.actorOf(Props(new Actor {
        def receive = {
          case "start" ⇒
            context.actorOf(Props(new Actor {
              def receive = { case x ⇒ sender ! x }
            }).withRouter(RoundRobinRouter(2))) ? "hello" pipeTo sender
        }
      })) ! "start"
      expectMsg("hello")
    }

  }

      @Test def `must be started when constructed`: Unit = {
      val routedActor = system.actorOf(Props[TestActor].withRouter(NoRouter))
      assertThat(routedActor.isTerminated, equalTo(false))
    }

    @Test def `must send message to connection`: Unit = {
      class Actor1 extends Actor {
        def receive = {
          case msg ⇒ testActor forward msg
        }
      }

      val routedActor = system.actorOf(Props(new Actor1).withRouter(NoRouter))
      routedActor ! "hello"
      routedActor ! "end"

      expectMsg("hello")
      expectMsg("end")
    }
  }

      @Test def `must be started when constructed`: Unit = {
      val routedActor = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(nrOfInstances = 1)))
      assertThat(routedActor.isTerminated, equalTo(false))
    }

    //In this test a bunch of actors are created and each actor has its own counter.
    //to test round robin, the routed actor receives the following sequence of messages 1 2 3 .. 1 2 3 .. 1 2 3 which it
    //uses to increment his counter.
    //So after n iteration, the first actor his counter should be 1*n, the second 2*n etc etc.
    @Test def `must deliver messages in a round robin fashion`: Unit = {
      val connectionCount = 10
      val iterationCount = 10
      val doneLatch = new TestLatch(connectionCount)

      //lets create some connections.
      @volatile var actors = immutable.IndexedSeq[ActorRef]()
      @volatile var counters = immutable.IndexedSeq[AtomicInteger]()
      for (i ← 0 until connectionCount) {
        counters = counters :+ new AtomicInteger()

        val actor = system.actorOf(Props(new Actor {
          def receive = {
            case "end"    ⇒ doneLatch.countDown()
            case msg: Int ⇒ counters(i).addAndGet(msg)
          }
        }))
        actors = actors :+ actor
      }

      val routedActor = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(routees = actors)))

      //send messages to the actor.
      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          routedActor ! (k + 1)
        }
      }

      routedActor ! Broadcast("end")
      //now wait some and do validations.
      Await.ready(doneLatch, remaining)

      for (i ← 0 until connectionCount)
        assertThat(counters(i).get, equalTo((iterationCount * (i + 1))))
    }

    @Test def `must deliver a broadcast message using the !`: Unit = {
      val doneLatch = new TestLatch(2)

      val counter1 = new AtomicInteger
      val actor1 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      }))

      val counter2 = new AtomicInteger
      val actor2 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      }))

      val routedActor = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(routees = List(actor1, actor2))))

      routedActor ! Broadcast(1)
      routedActor ! Broadcast("end")

      Await.ready(doneLatch, remaining)

      assertThat(counter1.get, equalTo(1))
      assertThat(counter2.get, equalTo(1))
    }
  }

  
    @Test def `must be started when constructed`: Unit = {
      val routedActor = system.actorOf(Props[TestActor].withRouter(RandomRouter(nrOfInstances = 1)))
      assertThat(routedActor.isTerminated, equalTo(false))
    }

    @Test def `must deliver a broadcast message`: Unit = {
      val doneLatch = new TestLatch(2)

      val counter1 = new AtomicInteger
      val actor1 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      }))

      val counter2 = new AtomicInteger
      val actor2 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      }))

      val routedActor = system.actorOf(Props[TestActor].withRouter(RandomRouter(routees = List(actor1, actor2))))

      routedActor ! Broadcast(1)
      routedActor ! Broadcast("end")

      Await.ready(doneLatch, remaining)

      assertThat(counter1.get, equalTo(1))
      assertThat(counter2.get, equalTo(1))
    }
  }

      @Test def `must be started when constructed`: Unit = {
      val routedActor = system.actorOf(Props[TestActor].withRouter(SmallestMailboxRouter(nrOfInstances = 1)))
      assertThat(routedActor.isTerminated, equalTo(false))
    }

    @Test def `must deliver messages to idle actor`: Unit = {
      val usedActors = new ConcurrentHashMap[Int, String]()
      val router = system.actorOf(Props(new Actor {
        def receive = {
          case (busy: TestLatch, receivedLatch: TestLatch) ⇒
            usedActors.put(0, self.path.toString)
            self ! "another in busy mailbox"
            receivedLatch.countDown()
            Await.ready(busy, TestLatch.DefaultTimeout)
          case (msg: Int, receivedLatch: TestLatch) ⇒
            usedActors.put(msg, self.path.toString)
            receivedLatch.countDown()
          case s: String ⇒
        }
      }).withRouter(SmallestMailboxRouter(3)))

      val busy = TestLatch(1)
      val received0 = TestLatch(1)
      router ! ((busy, received0))
      Await.ready(received0, TestLatch.DefaultTimeout)

      val received1 = TestLatch(1)
      router ! ((1, received1))
      Await.ready(received1, TestLatch.DefaultTimeout)

      val received2 = TestLatch(1)
      router ! ((2, received2))
      Await.ready(received2, TestLatch.DefaultTimeout)

      val received3 = TestLatch(1)
      router ! ((3, received3))
      Await.ready(received3, TestLatch.DefaultTimeout)

      busy.countDown()

      val busyPath = usedActors.get(0)
      assertThat(busyPath, notNullValue)

      val path1 = usedActors.get(1)
      val path2 = usedActors.get(2)
      val path3 = usedActors.get(3)

      assertThat(path1, not(busyPath))
      path2 must not be (busyPath)
      assertThat(path3, not(busyPath))

    }
  }

      @Test def `must be started when constructed`: Unit = {
      val routedActor = system.actorOf(Props[TestActor].withRouter(BroadcastRouter(nrOfInstances = 1)))
      assertThat(routedActor.isTerminated, equalTo(false))
    }

    @Test def `must broadcast message using !`: Unit = {
      val doneLatch = new TestLatch(2)

      val counter1 = new AtomicInteger
      val actor1 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      }))

      val counter2 = new AtomicInteger
      val actor2 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      }))

      val routedActor = system.actorOf(Props[TestActor].withRouter(BroadcastRouter(routees = List(actor1, actor2))))
      routedActor ! 1
      routedActor ! "end"

      Await.ready(doneLatch, remaining)

      assertThat(counter1.get, equalTo(1))
      assertThat(counter2.get, equalTo(1))
    }

    @Test def `must broadcast message using ?`: Unit = {
      val doneLatch = new TestLatch(2)

      val counter1 = new AtomicInteger
      val actor1 = system.actorOf(Props(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case msg: Int ⇒
            counter1.addAndGet(msg)
            sender ! "ack"
        }
      }))

      val counter2 = new AtomicInteger
      val actor2 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      }))

      val routedActor = system.actorOf(Props[TestActor].withRouter(BroadcastRouter(routees = List(actor1, actor2))))
      routedActor ? 1
      routedActor ! "end"

      Await.ready(doneLatch, remaining)

      assertThat(counter1.get, equalTo(1))
      assertThat(counter2.get, equalTo(1))
    }
  }

  
    @Test def `must be started when constructed`: Unit = {
      val routedActor = system.actorOf(Props[TestActor].withRouter(
        ScatterGatherFirstCompletedRouter(routees = List(newActor(0)), within = 1 seconds)))
      assertThat(routedActor.isTerminated, equalTo(false))
    }

    @Test def `must deliver a broadcast message using the !`: Unit = {
      val doneLatch = new TestLatch(2)

      val counter1 = new AtomicInteger
      val actor1 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      }))

      val counter2 = new AtomicInteger
      val actor2 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      }))

      val routedActor = system.actorOf(Props[TestActor].withRouter(
        ScatterGatherFirstCompletedRouter(routees = List(actor1, actor2), within = 1 seconds)))
      routedActor ! Broadcast(1)
      routedActor ! Broadcast("end")

      Await.ready(doneLatch, TestLatch.DefaultTimeout)

      assertThat(counter1.get, equalTo(1))
      assertThat(counter2.get, equalTo(1))
    }

    @Test def `must return response, even if one of the actors has stopped`: Unit = {
      val shutdownLatch = new TestLatch(1)
      val actor1 = newActor(1, Some(shutdownLatch))
      val actor2 = newActor(14, Some(shutdownLatch))
      val routedActor = system.actorOf(Props[TestActor].withRouter(
        ScatterGatherFirstCompletedRouter(routees = List(actor1, actor2), within = 3 seconds)))

      routedActor ! Broadcast(Stop(Some(1)))
      Await.ready(shutdownLatch, TestLatch.DefaultTimeout)
      assertThat(Await.result(routedActor ? Broadcast(0), timeout.duration), equalTo(14))
    }

    case class Stop(id: Option[Int] = None)

    def newActor(id: Int, shudownLatch: Option[TestLatch] = None) = system.actorOf(Props(new Actor {
      def receive = {
        case Stop(None)                     ⇒ context.stop(self)
        case Stop(Some(_id)) if (_id == id) ⇒ context.stop(self)
        case _id: Int if (_id == id)        ⇒
        case x ⇒ {
          Thread sleep 100 * id
          sender ! id
        }
      }

      override def postStop = {
        shudownLatch foreach (_.countDown())
      }
    }), "Actor:" + id)
  }

      @Test def `must throw suitable exception when not configured`: Unit = {
      val e = intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withRouter(FromConfig), "routerNotDefined")
      }
      e.getMessage must include("routerNotDefined")
    }

    @Test def `must allow external configuration`: Unit = {
      val sys = ActorSystem("FromConfig", ConfigFactory
        .parseString("akka.actor.deployment./routed.router=round-robin")
        .withFallback(system.settings.config))
      try {
        sys.actorOf(Props.empty.withRouter(FromConfig), "routed")
      } finally {
        shutdown(sys)
      }
    }

    @Test def `must support custom router`: Unit = {
      val myrouter = system.actorOf(Props.empty.withRouter(FromConfig), "myrouter")
      assertThat(myrouter.isTerminated, equalTo(false))
    }
  }

      @Test def `must be started when constructed`: Unit = {
      val routedActor = system.actorOf(Props[TestActor].withRouter(VoteCountRouter()))
      assertThat(routedActor.isTerminated, equalTo(false))
    }

    @Test def `must count votes as intended - not as in Florida`: Unit = {
      val routedActor = system.actorOf(Props.empty.withRouter(VoteCountRouter()))
      routedActor ! DemocratVote
      routedActor ! DemocratVote
      routedActor ! RepublicanVote
      routedActor ! DemocratVote
      routedActor ! RepublicanVote
      val democratsResult = (routedActor ? DemocratCountResult)
      val republicansResult = (routedActor ? RepublicanCountResult)

      Await.result(democratsResult, 1 seconds) === 3
      Await.result(republicansResult, 1 seconds) === 2
    }

    // DO NOT CHANGE THE COMMENTS BELOW AS THEY ARE USED IN THE DOCUMENTATION

    //#CustomRouter
    //#crMessages
    case object DemocratVote
    case object DemocratCountResult
    case object RepublicanVote
    case object RepublicanCountResult
    //#crMessages

    //#crActors
    class DemocratActor extends Actor {
      var counter = 0

      def receive = {
        case DemocratVote        ⇒ counter += 1
        case DemocratCountResult ⇒ sender ! counter
      }
    }

    class RepublicanActor extends Actor {
      var counter = 0

      def receive = {
        case RepublicanVote        ⇒ counter += 1
        case RepublicanCountResult ⇒ sender ! counter
      }
    }
    //#crActors

    //#crRouter
    case class VoteCountRouter() extends RouterConfig {

      def routerDispatcher: String = Dispatchers.DefaultDispatcherId
      def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.defaultStrategy

      //#crRoute
      def createRoute(routeeProvider: RouteeProvider): Route = {
        val democratActor =
          routeeProvider.context.actorOf(Props(new DemocratActor()), "d")
        val republicanActor =
          routeeProvider.context.actorOf(Props(new RepublicanActor()), "r")
        val routees = Vector[ActorRef](democratActor, republicanActor)

        //#crRegisterRoutees
        routeeProvider.registerRoutees(routees)
        //#crRegisterRoutees

        //#crRoutingLogic
        {
          case (sender, message) ⇒
            message match {
              case DemocratVote | DemocratCountResult ⇒
                List(Destination(sender, democratActor))
              case RepublicanVote | RepublicanCountResult ⇒
                List(Destination(sender, republicanActor))
            }
        }
        //#crRoutingLogic
      }
      //#crRoute

    }
    //#crRouter
    //#CustomRouter
  }