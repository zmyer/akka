package akka.testkit

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.actor._
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import akka.pattern.ask

class JavaTestKitSpec extends AkkaSpec with DefaultTimeout {

  
    @Test def `must be able to receiveN messages`: Unit = {
      new JavaTestKit(system) {
        val sent = List(1, 2, 3, 4, 5)
        for (m ← sent) { getRef() ! m }
        val received = receiveN(sent.size, 5 seconds)
        assertThat(sent.toSet, equalTo(received.toSet))
      }
    }

    @Test def `must be able to receiveN messages with default duration`: Unit = {
      new JavaTestKit(system) {
        val sent = List(1, 2, 3)
        for (m ← sent) { getRef() ! m }
        val received = receiveN(sent.size)
        assertThat(sent.toSet, equalTo(received.toSet))
      }
    }

    @Test def `must be able to expectTerminated`: Unit = {
      new JavaTestKit(system) {
        val actor = system.actorOf(Props(new Actor { def receive = { case _ ⇒ } }))

        watch(actor)
        system stop actor
        assertThat(expectTerminated(actor).existenceConfirmed, equalTo(true))

        watch(actor)
        assertThat(expectTerminated(5 seconds, actor).actor, equalTo(actor))
      }
    }

  }