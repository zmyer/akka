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

object ActorLookupSpec {

  case class Create(child: String)

  trait Query
  case class LookupElems(path: Iterable[String]) extends Query
  case class LookupString(path: String) extends Query
  case class LookupPath(path: ActorPath) extends Query
  case class GetSender(to: ActorRef) extends Query

  val p = Props[Node]

  class Node extends Actor {
    def receive = {
      case Create(name)       ⇒ sender ! context.actorOf(p, name)
      case LookupElems(path)  ⇒ sender ! context.actorFor(path)
      case LookupString(path) ⇒ sender ! context.actorFor(path)
      case LookupPath(path)   ⇒ sender ! context.actorFor(path)
      case GetSender(ref)     ⇒ ref ! sender
    }
  }

}

class ActorLookupSpec extends AkkaSpec with DefaultTimeout {
  import ActorLookupSpec._

  val c1 = system.actorOf(p, "c1")
  val c2 = system.actorOf(p, "c2")
  val c21 = Await.result((c2 ? Create("c21")).mapTo[ActorRef], timeout.duration)

  val sysImpl = system.asInstanceOf[ActorSystemImpl]

  val user = sysImpl.guardian
  val syst = sysImpl.systemGuardian
  val root = sysImpl.lookupRoot

  def empty(path: String) =
    new EmptyLocalActorRef(sysImpl.provider, path match {
      case RelativeActorPath(elems) ⇒ system.actorFor("/").path / elems
    }, system.eventStream)

  
    @Test def `must find actors by looking up their path`: Unit = {
      assertThat(system.actorFor(c1.path), equalTo(c1))
      system.actorFor(c2.path) must be === c2
      assertThat(system.actorFor(c21.path), equalTo(c21))
      system.actorFor(system / "c1") must be === c1
      assertThat(system.actorFor(system / "c2"), equalTo(c2))
      system.actorFor(system / "c2" / "c21") must be === c21
      assertThat(system.actorFor(system child "c2" child "c21"), equalTo(c21 // test Java API))
      system.actorFor(system / Seq("c2", "c21")) must be === c21

      import scala.collection.JavaConverters._
      system.actorFor(system descendant Seq("c2", "c21").asJava) // test Java API
    }

    @Test def `must find actors by looking up their string representation`: Unit = {
      // this is only true for local actor references
      assertThat(system.actorFor(c1.path.toString), equalTo(c1))
      system.actorFor(c2.path.toString) must be === c2
      assertThat(system.actorFor(c21.path.toString), equalTo(c21))
    }

    @Test def `must take actor incarnation into account when comparing actor references`: Unit = {
      val name = "abcdefg"
      val a1 = system.actorOf(p, name)
      watch(a1)
      a1 ! PoisonPill
      expectTerminated(a1)

      // let it be completely removed from user guardian
      expectNoMsg(1 second)

      // not equal because it's terminated
      assertThat(system.actorFor(a1.path.toString), not(a1))

      val a2 = system.actorOf(p, name)
      assertThat(a2.path, equalTo(a1.path))
      assertThat(a2.path.toString, equalTo(a1.path.toString))
      assertThat(a2, not(a1))
      a2.toString must not be (a1.toString)

      watch(a2)
      a2 ! PoisonPill
      expectTerminated(a2)
    }

    @Test def `must find actors by looking up their root-anchored relative path`: Unit = {
      assertThat(system.actorFor(c1.path.elements.mkString("/", "/", "")), equalTo(c1))
      system.actorFor(c2.path.elements.mkString("/", "/", "")) must be === c2
      assertThat(system.actorFor(c21.path.elements.mkString("/", "/", "")), equalTo(c21))
    }

    @Test def `must find actors by looking up their relative path`: Unit = {
      assertThat(system.actorFor(c1.path.elements.mkString("/")), equalTo(c1))
      system.actorFor(c2.path.elements.mkString("/")) must be === c2
      assertThat(system.actorFor(c21.path.elements.mkString("/")), equalTo(c21))
    }

    @Test def `must find actors by looking up their path elements`: Unit = {
      assertThat(system.actorFor(c1.path.elements), equalTo(c1))
      system.actorFor(c2.path.elements) must be === c2
      assertThat(system.actorFor(c21.path.getElements), equalTo(c21 // test Java API))
    }

    @Test def `must find system-generated actors`: Unit = {
      assertThat(system.actorFor("/user"), equalTo(user))
      system.actorFor("/deadLetters") must be === system.deadLetters
      assertThat(system.actorFor("/system"), equalTo(syst))
      system.actorFor(syst.path) must be === syst
      assertThat(system.actorFor(syst.path.toString), equalTo(syst))
      system.actorFor("/") must be === root
      assertThat(system.actorFor(".."), equalTo(root))
      system.actorFor(root.path) must be === root
      assertThat(system.actorFor(root.path.toString), equalTo(root))
      system.actorFor("user") must be === user
      assertThat(system.actorFor("deadLetters"), equalTo(system.deadLetters))
      system.actorFor("system") must be === syst
      assertThat(system.actorFor("user/"), equalTo(user))
      system.actorFor("deadLetters/") must be === system.deadLetters
      assertThat(system.actorFor("system/"), equalTo(syst))
    }

    @Test def `must return deadLetters or EmptyLocalActorRef, respectively, for non-existing paths`: Unit = {
      def check(lookup: ActorRef, result: ActorRef) = {
        assertThat(lookup.getClass, equalTo(result.getClass))
        lookup must be === result
      }
      check(system.actorFor("a/b/c"), empty("a/b/c"))
      check(system.actorFor(""), system.deadLetters)
      check(system.actorFor("akka://all-systems/Nobody"), system.deadLetters)
      check(system.actorFor("akka://all-systems/user"), system.deadLetters)
      check(system.actorFor(system / "hallo"), empty("user/hallo"))
      check(system.actorFor(Seq()), system.deadLetters)
      check(system.actorFor(Seq("a")), empty("a"))
    }

    @Test def `must find temporary actors`: Unit = {
      val f = c1 ? GetSender(testActor)
      val a = expectMsgType[ActorRef]
      assertThat(a.path.elements.head, equalTo("temp"))
      system.actorFor(a.path) must be === a
      assertThat(system.actorFor(a.path.toString), equalTo(a))
      system.actorFor(a.path.elements) must be === a
      assertThat(system.actorFor(a.path.toString + "/"), equalTo(a))
      system.actorFor(a.path.toString + "/hallo").isTerminated must be === true
      assertThat(f.isCompleted, equalTo(false))
      a.isTerminated must be === false
      a ! 42
      assertThat(f.isCompleted, equalTo(true))
      Await.result(f, timeout.duration) must be === 42
      // clean-up is run as onComplete callback, i.e. dispatched on another thread
      awaitCond(system.actorFor(a.path).isTerminated, 1 second)
    }

  }

  
    val all = Seq(c1, c2, c21)

    @Test def `must find actors by looking up their path`: Unit = {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        assertThat(Await.result(looker ? LookupPath(pathOf.path), timeout.duration), equalTo(result))
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    @Test def `must find actors by looking up their string representation`: Unit = {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        assertThat(Await.result(looker ? LookupString(pathOf.path.toString), timeout.duration), equalTo(result))
        // with uid
        assertThat(Await.result(looker ? LookupString(pathOf.path.toSerializationFormat), timeout.duration), equalTo(result))
        // with trailing /
        assertThat(Await.result(looker ? LookupString(pathOf.path.toString + "/"), timeout.duration), equalTo(result))
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    @Test def `must find actors by looking up their root-anchored relative path`: Unit = {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        assertThat(Await.result(looker ? LookupString(pathOf.path.elements.mkString("/", "/", "")), timeout.duration), equalTo(result))
        Await.result(looker ? LookupString(pathOf.path.elements.mkString("/", "/", "/")), timeout.duration) must be === result
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    @Test def `must find actors by looking up their relative path`: Unit = {
      def check(looker: ActorRef, result: ActorRef, elems: String*) {
        assertThat(Await.result(looker ? LookupElems(elems), timeout.duration), equalTo(result))
        Await.result(looker ? LookupString(elems mkString "/"), timeout.duration) must be === result
        assertThat(Await.result(looker ? LookupString(elems mkString ("", "/", "/")), timeout.duration), equalTo(result))
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
          assertThat(Await.result(looker ? LookupPath(target.path), timeout.duration), equalTo(target))
          Await.result(looker ? LookupString(target.path.toString), timeout.duration) must be === target
          assertThat(Await.result(looker ? LookupString(target.path.toString + "/"), timeout.duration), equalTo(target))
          Await.result(looker ? LookupString(target.path.elements.mkString("/", "/", "")), timeout.duration) must be === target
          assertThat(if (target != root) Await.result(looker ? LookupString(target.path.elements.mkString("/", "/", "/")), timeout.duration), equalTo(target))
        }
      }
      for (target ← Seq(root, syst, user, system.deadLetters)) check(target)
    }

    @Test def `must return deadLetters or EmptyLocalActorRef, respectively, for non-existing paths`: Unit = {
      import scala.collection.JavaConverters._

      def checkOne(looker: ActorRef, query: Query, result: ActorRef) {
        val lookup = Await.result(looker ? query, timeout.duration)
        assertThat(lookup.getClass, equalTo(result.getClass))
        lookup must be === result
      }
      def check(looker: ActorRef) {
        val lookname = looker.path.elements.mkString("", "/", "/")
        for (
          (l, r) ← Seq(
            LookupString("a/b/c") -> empty(lookname + "a/b/c"),
            LookupString("") -> system.deadLetters,
            LookupString("akka://all-systems/Nobody") -> system.deadLetters,
            LookupPath(system / "hallo") -> empty("user/hallo"),
            LookupPath(looker.path child "hallo") -> empty(lookname + "hallo"), // test Java API
            LookupPath(looker.path descendant Seq("a", "b").asJava) -> empty(lookname + "a/b"), // test Java API
            LookupElems(Seq()) -> system.deadLetters,
            LookupElems(Seq("a")) -> empty(lookname + "a"))
        ) checkOne(looker, l, r)
      }
      for (looker ← all) check(looker)
    }

    @Test def `must find temporary actors`: Unit = {
      val f = c1 ? GetSender(testActor)
      val a = expectMsgType[ActorRef]
      assertThat(a.path.elements.head, equalTo("temp"))
      Await.result(c2 ? LookupPath(a.path), timeout.duration) must be === a
      assertThat(Await.result(c2 ? LookupString(a.path.toString), timeout.duration), equalTo(a))
      Await.result(c2 ? LookupString(a.path.elements.mkString("/", "/", "")), timeout.duration) must be === a
      assertThat(Await.result(c2 ? LookupString("../../" + a.path.elements.mkString("/")), timeout.duration), equalTo(a))
      Await.result(c2 ? LookupString(a.path.toString + "/"), timeout.duration) must be === a
      assertThat(Await.result(c2 ? LookupString(a.path.elements.mkString("/", "/", "") + "/"), timeout.duration), equalTo(a))
      Await.result(c2 ? LookupString("../../" + a.path.elements.mkString("/") + "/"), timeout.duration) must be === a
      assertThat(Await.result(c2 ? LookupElems(Seq("..", "..") ++ a.path.elements), timeout.duration), equalTo(a))
      Await.result(c2 ? LookupElems(Seq("..", "..") ++ a.path.elements :+ ""), timeout.duration) must be === a
      assertThat(f.isCompleted, equalTo(false))
      a.isTerminated must be === false
      a ! 42
      assertThat(f.isCompleted, equalTo(true))
      Await.result(f, timeout.duration) must be === 42
      // clean-up is run as onComplete callback, i.e. dispatched on another thread
      awaitCond(Await.result(c2 ? LookupPath(a.path), timeout.duration).asInstanceOf[ActorRef].isTerminated, 1 second)
    }

  }