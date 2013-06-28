/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.pattern

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import akka.testkit.AkkaSpec
import akka.testkit.TestProbe
import scala.concurrent.Future
import akka.actor.Status

class PipeToSpec extends AkkaSpec {

  import system.dispatcher

  
    @Test def `must work`: Unit = {
      val p = TestProbe()
      Future(42) pipeTo p.ref
      p.expectMsg(42)
    }

    @Test def `must signal failure`: Unit = {
      val p = TestProbe()
      Future.failed(new Exception("failed")) pipeTo p.ref
      assertThat(p.expectMsgType[Status.Failure].cause.getMessage, equalTo("failed"))
    }

    @Test def `must pick up an implicit sender`: Unit = {
      val p = TestProbe()
      implicit val s = testActor
      Future(42) pipeTo p.ref
      p.expectMsg(42)
      assertThat(p.lastSender, equalTo(s))
    }

    @Test def `must work in Java form`: Unit = {
      val p = TestProbe()
      pipe(Future(42)) to p.ref
      p.expectMsg(42)
    }

    @Test def `must work in Java form with sender`: Unit = {
      val p = TestProbe()
      pipe(Future(42)) to (p.ref, testActor)
      p.expectMsg(42)
      assertThat(p.lastSender, equalTo(testActor))
    }

  }

  
    @Test def `must work`: Unit = {
      val p = TestProbe()
      val sel = system.actorSelection(p.ref.path)
      Future(42) pipeToSelection sel
      p.expectMsg(42)
    }

    @Test def `must signal failure`: Unit = {
      val p = TestProbe()
      val sel = system.actorSelection(p.ref.path)
      Future.failed(new Exception("failed")) pipeToSelection sel
      assertThat(p.expectMsgType[Status.Failure].cause.getMessage, equalTo("failed"))
    }

    @Test def `must pick up an implicit sender`: Unit = {
      val p = TestProbe()
      val sel = system.actorSelection(p.ref.path)
      implicit val s = testActor
      Future(42) pipeToSelection sel
      p.expectMsg(42)
      assertThat(p.lastSender, equalTo(s))
    }

    @Test def `must work in Java form`: Unit = {
      val p = TestProbe()
      val sel = system.actorSelection(p.ref.path)
      pipe(Future(42)) to sel
      p.expectMsg(42)
    }

    @Test def `must work in Java form with sender`: Unit = {
      val p = TestProbe()
      val sel = system.actorSelection(p.ref.path)
      pipe(Future(42)) to (sel, testActor)
      p.expectMsg(42)
      assertThat(p.lastSender, equalTo(testActor))
    }

  }

}