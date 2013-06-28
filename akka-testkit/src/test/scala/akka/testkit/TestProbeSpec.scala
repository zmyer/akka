package akka.testkit

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.actor._
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import akka.pattern.ask

class TestProbeSpec extends AkkaSpec with DefaultTimeout {

  @Test def `must reply to futures`: Unit = {
    val tk = TestProbe()
    val future = tk.ref ? "hello"
    tk.expectMsg(0 millis, "hello") // TestActor runs on CallingThreadDispatcher
    tk.lastMessage.sender ! "world"
    assertThat(future, equalTo('completed))
    assertThat(Await.result(future, timeout.duration), equalTo("world"))
  }

  @Test def `must reply to messages`: Unit = {
    val tk1 = TestProbe()
    val tk2 = TestProbe()
    tk1.ref.!("hello")(tk2.ref)
    tk1.expectMsg(0 millis, "hello")
    tk1.lastMessage.sender ! "world"
    tk2.expectMsg(0 millis, "world")
  }

  @Test def `must properly send and reply to messages`: Unit = {
    val probe1 = TestProbe()
    val probe2 = TestProbe()
    probe1.send(probe2.ref, "hello")
    probe2.expectMsg(0 millis, "hello")
    probe2.lastMessage.sender ! "world"
    probe1.expectMsg(0 millis, "world")
  }

  @Test def `must have an AutoPilot`: Unit = {
    //#autopilot
    val probe = TestProbe()
    probe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case "stop" ⇒ TestActor.NoAutoPilot
          case x      ⇒ testActor.tell(x, sender); TestActor.KeepRunning
        }
    })
    //#autopilot
    probe.ref ! "hallo"
    probe.ref ! "welt"
    probe.ref ! "stop"
    expectMsg("hallo")
    expectMsg("welt")
    probe.expectMsg("hallo")
    probe.expectMsg("welt")
    probe.expectMsg("stop")
    probe.ref ! "hallo"
    probe.expectMsg("hallo")
    testActor ! "end"
    expectMsg("end") // verify that "hallo" did not get through
  }

  @Test def `must be able to expect primitive types`: Unit = {
    for (_ ← 1 to 7) testActor ! 42
    assertThat(expectMsgType[Int], equalTo(42))
    assertThat(expectMsgAnyClassOf(classOf[Int]), equalTo(42))
    assertThat(expectMsgAllClassOf(classOf[Int]), equalTo(Seq(42)))
    assertThat(expectMsgAllConformingOf(classOf[Int]), equalTo(Seq(42)))
    assertThat(expectMsgAllConformingOf(5 seconds, classOf[Int]), equalTo(Seq(42)))
    assertThat(expectMsgAllClassOf(classOf[Int]), equalTo(Seq(42)))
    assertThat(expectMsgAllClassOf(5 seconds, classOf[Int]), equalTo(Seq(42)))
  }

  @Test def `must be able to ignore primitive types`: Unit = {
    ignoreMsg { case 42 ⇒ true }
    testActor ! 42
    testActor ! "pigdog"
    expectMsg("pigdog")
  }

  @Test def `must watch actors when queue non-empty`: Unit = {
    val probe = TestProbe()
    // deadLetters does not send Terminated
    val target = system.actorOf(Props(new Actor {
      def receive = Actor.emptyBehavior
    }))
    system.stop(target)
    probe.ref ! "hello"
    probe watch target
    probe.expectMsg(1.seconds, "hello")
    probe.expectMsg(1.seconds, Terminated(target)(false, false))
  }

}