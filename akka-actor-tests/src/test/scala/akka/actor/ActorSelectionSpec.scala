/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.testkit._
import scala.concurrent.duration._
import scala.concurrent.Await
import akka.pattern.ask

object ActorSelectionSpec {

  case class Create(child: String)

  trait Query
  case class SelectString(path: String) extends Query
  case class SelectPath(path: ActorPath) extends Query
  case class GetSender(to: ActorRef) extends Query

  val p = Props[Node]

  class Node extends Actor {
    def receive = {
      case Create(name)       ⇒ sender ! context.actorOf(p, name)
      case SelectString(path) ⇒ sender ! context.actorSelection(path)
      case SelectPath(path)   ⇒ sender ! context.actorSelection(path)
      case GetSender(ref)     ⇒ ref ! sender
    }
  }

}

class ActorSelectionSpec extends AkkaSpec("akka.loglevel=DEBUG") with DefaultTimeout {
  import ActorSelectionSpec._

  val c1 = system.actorOf(p, "c1")
  val c2 = system.actorOf(p, "c2")
  val c21 = Await.result((c2 ? Create("c21")).mapTo[ActorRef], timeout.duration)

  val sysImpl = system.asInstanceOf[ActorSystemImpl]

  val user = sysImpl.guardian
  val syst = sysImpl.systemGuardian
  val root = sysImpl.lookupRoot

  def empty(path: String) =
    new EmptyLocalActorRef(sysImpl.provider, path match {
      case RelativeActorPath(elems) ⇒ sysImpl.lookupRoot.path / elems
    }, system.eventStream)

  val idProbe = TestProbe()

  def identify(selection: ActorSelection): Option[ActorRef] = {
    selection.tell(Identify(selection), idProbe.ref)
    val result = idProbe.expectMsgPF() {
      case ActorIdentity(`selection`, ref) ⇒ ref
    }
    val asked = Await.result((selection ? Identify(selection)).mapTo[ActorIdentity], timeout.duration)
    assertThat(asked.ref, equalTo(result))
    assertThat(asked.correlationId, equalTo(selection))
    result
  }

  def identify(path: String): Option[ActorRef] = identify(system.actorSelection(path))
  def identify(path: ActorPath): Option[ActorRef] = identify(system.actorSelection(path))

  def askNode(node: ActorRef, query: Query): Option[ActorRef] = {
    Await.result(node ? query, timeout.duration) match {
      case ref: ActorRef             ⇒ Some(ref)
      case selection: ActorSelection ⇒ identify(selection)
    }
  }

  
    @Test def `must select actors by their path`: Unit = {
      assertThat(identify(c1.path), equalTo(Some(c1)))
      identify(c2.path) must be === Some(c2)
      assertThat(identify(c21.path), equalTo(Some(c21)))
      identify(system / "c1") must be === Some(c1)
      assertThat(identify(system / "c2"), equalTo(Some(c2)))
      identify(system / "c2" / "c21") must be === Some(c21)
      assertThat(identify(system child "c2" child "c21"), equalTo(Some(c21) // test Java API))
      identify(system / Seq("c2", "c21")) must be === Some(c21)

      import scala.collection.JavaConverters._
      identify(system descendant Seq("c2", "c21").asJava) // test Java API
    }

    @Test def `must select actors by their string path representation`: Unit = {
      assertThat(identify(c1.path.toString), equalTo(Some(c1)))
      identify(c2.path.toString) must be === Some(c2)
      assertThat(identify(c21.path.toString), equalTo(Some(c21)))

      assertThat(identify(c1.path.elements.mkString("/", "/", "")), equalTo(Some(c1)))
      identify(c2.path.elements.mkString("/", "/", "")) must be === Some(c2)
      assertThat(identify(c21.path.elements.mkString("/", "/", "")), equalTo(Some(c21)))
    }

    @Test def `must take actor incarnation into account when comparing actor references`: Unit = {
      val name = "abcdefg"
      val a1 = system.actorOf(p, name)
      watch(a1)
      a1 ! PoisonPill
      assertThat(expectMsgType[Terminated].actor, equalTo(a1))

      // not equal because it's terminated
      assertThat(identify(a1.path), equalTo(None))

      val a2 = system.actorOf(p, name)
      assertThat(a2.path, equalTo(a1.path))
      assertThat(a2.path.toString, equalTo(a1.path.toString))
      assertThat(a2, not(a1))
      a2.toString must not be (a1.toString)

      watch(a2)
      a2 ! PoisonPill
      assertThat(expectMsgType[Terminated].actor, equalTo(a2))
    }

    @Test def `must select actors by their root-anchored relative path`: Unit = {
      assertThat(identify(c1.path.elements.mkString("/", "/", "")), equalTo(Some(c1)))
      identify(c2.path.elements.mkString("/", "/", "")) must be === Some(c2)
      assertThat(identify(c21.path.elements.mkString("/", "/", "")), equalTo(Some(c21)))
    }

    @Test def `must select actors by their relative path`: Unit = {
      assertThat(identify(c1.path.elements.mkString("/")), equalTo(Some(c1)))
      identify(c2.path.elements.mkString("/")) must be === Some(c2)
      assertThat(identify(c21.path.elements.mkString("/")), equalTo(Some(c21)))
    }

    @Test def `must select system-generated actors`: Unit = {
      assertThat(identify("/user"), equalTo(Some(user)))
      identify("/deadLetters") must be === Some(system.deadLetters)
      assertThat(identify("/system"), equalTo(Some(syst)))
      identify(syst.path) must be === Some(syst)
      assertThat(identify(syst.path.elements.mkString("/", "/", "")), equalTo(Some(syst)))
      identify("/") must be === Some(root)
      assertThat(identify(""), equalTo(Some(root)))
      identify(RootActorPath(root.path.address)) must be === Some(root)
      assertThat(identify(".."), equalTo(Some(root)))
      identify(root.path) must be === Some(root)
      assertThat(identify(root.path.elements.mkString("/", "/", "")), equalTo(Some(root)))
      identify("user") must be === Some(user)
      assertThat(identify("deadLetters"), equalTo(Some(system.deadLetters)))
      identify("system") must be === Some(syst)
      assertThat(identify("user/"), equalTo(Some(user)))
      identify("deadLetters/") must be === Some(system.deadLetters)
      assertThat(identify("system/"), equalTo(Some(syst)))
    }

    @Test def `must return deadLetters or ActorIdentity(None), respectively, for non-existing paths`: Unit = {
      assertThat(identify("a/b/c"), equalTo(None))
      identify("a/b/c") must be === None
      assertThat(identify("akka://all-systems/Nobody"), equalTo(None))
      identify("akka://all-systems/user") must be === None
      assertThat(identify(system / "hallo"), equalTo(None))
    }

  }

  
    val all = Seq(c1, c2, c21)

    @Test def `must select actors by their path`: Unit = {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        assertThat(askNode(looker, SelectPath(pathOf.path)), equalTo(Some(result)))
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    @Test def `must select actors by their string path representation`: Unit = {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        assertThat(askNode(looker, SelectString(pathOf.path.elements.mkString("/", "/", ""))), equalTo(Some(result)))
        // with trailing /
        assertThat(askNode(looker, SelectString(pathOf.path.elements.mkString("/", "/", "") + "/")), equalTo(Some(result)))
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    @Test def `must select actors by their root-anchored relative path`: Unit = {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        assertThat(askNode(looker, SelectString(pathOf.path.elements.mkString("/", "/", ""))), equalTo(Some(result)))
        askNode(looker, SelectString(pathOf.path.elements.mkString("/", "/", "/"))) must be === Some(result)
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    @Test def `must select actors by their relative path`: Unit = {
      def check(looker: ActorRef, result: ActorRef, elems: String*) {
        assertThat(askNode(looker, SelectString(elems mkString "/")), equalTo(Some(result)))
        askNode(looker, SelectString(elems mkString ("", "/", "/"))) must be === Some(result)
      }
      check(c1, user, "..")
      for {
        looker ← Seq(c1, c2)
        target ← all
      } check(looker, target, Seq("..") ++ target.path.elements.drop(1): _*)
      check(c21, user, "..", "..")
      check(c21, root, "..", "..", "..")
      check(c21, root, "..", "..", "..", "..")
    }

    @Test def `must find system-generated actors`: Unit = {
      def check(target: ActorRef) {
        for (looker ← all) {
          assertThat(askNode(looker, SelectPath(target.path)), equalTo(Some(target)))
          askNode(looker, SelectString(target.path.toString)) must be === Some(target)
          assertThat(askNode(looker, SelectString(target.path.toString + "/")), equalTo(Some(target)))
        }
        if (target != root)
          assertThat(askNode(c1, SelectString("../.." + target.path.elements.mkString("/", "/", "/"))), equalTo(Some(target)))
      }
      for (target ← Seq(root, syst, user)) check(target)
    }

    @Test def `must return deadLetters or ActorIdentity(None), respectively, for non-existing paths`: Unit = {
      import scala.collection.JavaConverters._

      def checkOne(looker: ActorRef, query: Query, result: Option[ActorRef]) {
        val lookup = askNode(looker, query)
        assertThat(lookup, equalTo(result))
      }
      def check(looker: ActorRef) {
        val lookname = looker.path.elements.mkString("", "/", "/")
        for (
          (l, r) ← Seq(
            SelectString("a/b/c") -> None,
            SelectString("akka://all-systems/Nobody") -> None,
            SelectPath(system / "hallo") -> None,
            SelectPath(looker.path child "hallo") -> None, // test Java API
            SelectPath(looker.path descendant Seq("a", "b").asJava) -> None) // test Java API
        ) checkOne(looker, l, r)
      }
      for (looker ← all) check(looker)
    }

  }

  
    @Test def `must send messages directly`: Unit = {
      ActorSelection(c1, "") ! GetSender(testActor)
      expectMsg(system.deadLetters)
      assertThat(lastSender, equalTo(c1))
    }

    @Test def `must send messages to string path`: Unit = {
      system.actorSelection("/user/c2/c21") ! GetSender(testActor)
      expectMsg(system.deadLetters)
      assertThat(lastSender, equalTo(c21))
    }

    @Test def `must send messages to actor path`: Unit = {
      system.actorSelection(system / "c2" / "c21") ! GetSender(testActor)
      expectMsg(system.deadLetters)
      assertThat(lastSender, equalTo(c21))
    }

    @Test def `must send messages with correct sender`: Unit = {
      implicit val sender = c1
      ActorSelection(c21, "../../*") ! GetSender(testActor)
      val actors = Set() ++ receiveWhile(messages = 2) {
        case `c1` ⇒ lastSender
      }
      assertThat(actors, equalTo(Set(c1, c2)))
      expectNoMsg(1 second)
    }

    @Test def `must drop messages which cannot be delivered`: Unit = {
      implicit val sender = c2
      ActorSelection(c21, "../../*/c21") ! GetSender(testActor)
      val actors = receiveWhile(messages = 2) {
        case `c2` ⇒ lastSender
      }
      assertThat(actors, equalTo(Seq(c21)))
      expectNoMsg(1 second)
    }

    @Test def `must compare equally`: Unit = {
      assertThat(ActorSelection(c21, "../*/hello"), equalTo(ActorSelection(c21, "../*/hello")))
      ActorSelection(c21, "../*/hello").## must be === ActorSelection(c21, "../*/hello").##
      ActorSelection(c2, "../*/hello") must not be ActorSelection(c21, "../*/hello")
      ActorSelection(c2, "../*/hello").## must not be ActorSelection(c21, "../*/hello").##
      ActorSelection(c21, "../*/hell") must not be ActorSelection(c21, "../*/hello")
      ActorSelection(c21, "../*/hell").## must not be ActorSelection(c21, "../*/hello").##
    }

    @Test def `must print nicely`: Unit = {
      assertThat(ActorSelection(c21, "../*/hello").toString, equalTo(s"ActorSelection[Actor[akka://ActorSelectionSpec/user/c2/c21#${c21.path.uid}]/../*/hello]"))
    }

  }