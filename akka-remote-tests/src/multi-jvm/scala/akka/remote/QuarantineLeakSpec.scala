/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ExtendedActorSystem
import akka.actor.Props
import akka.actor.RootActorPath
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory

object QuarantineLeakSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("""
      akka.loglevel = INFO
      akka.remote.log-remote-lifecycle-events = INFO
      akka.remote.quarantine-systems-for = 1 d
      akka.remote.gate-invalid-addresses-for = 0.5 s
      """)))

  class Subject extends Actor {
    def receive = {
      case "identify" ⇒ sender() ! (AddressUidExtension(context.system).addressUid, self)
    }
  }

}

class QuarantineLeakMultiJvmNode1 extends QuarantineLeakSpec
class QuarantineLeakMultiJvmNode2 extends QuarantineLeakSpec

abstract class QuarantineLeakSpec extends MultiNodeSpec(QuarantineLeakSpec)
  with STMultiNodeSpec
  with ImplicitSender {

  import QuarantineLeakSpec._

  override def initialParticipants = roles.size

  def identify(role: RoleName, actorName: String): (Int, ActorRef) = {
    system.actorSelection(node(role) / "user" / actorName) ! "identify"
    expectMsgType[(Int, ActorRef)]
  }

  "QuarantineLeakSpec" must {

    "demonstrate memory leak" taggedAs LongRunningTest in {

      runOn(first) {
        system.actorOf(Props[Subject], "subject")
        enterBarrier("actors-started")

        // Acquire uid from second system
        val (uidSecond, subjectSecond) = identify(second, "subject")
        enterBarrier("actor-identified")

        // Manually Quarantine the other system
        RARP(system).provider.transport.quarantine(node(second).address, Some(uidSecond))

        Thread.sleep(1000)
        enterBarrier("quarantine-in-place")
      }

      runOn(second) {
        val firstAddress = node(first).address
        val addr = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        system.actorOf(Props[Subject], "subject")
        enterBarrier("actors-started")

        enterBarrier("actor-identified")
        enterBarrier("quarantine-in-place")

        println(s"""=====> Observe: netstat -an | egrep '${addr.port.get}|${firstAddress.port.get}' | wc -l """)
        // Quarantine is up -- Cannot communicate with remote system any more
        for (_ ← 1 to 100) {
          system.actorSelection(RootActorPath(firstAddress) / "user" / "subject") ! "identify"
          expectNoMsg(2.seconds)
        }
      }

      within(4.minutes) {
        enterBarrier("done")
      }

    }

  }
}
