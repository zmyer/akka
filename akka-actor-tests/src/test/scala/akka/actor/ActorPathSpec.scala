/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import java.net.MalformedURLException

class ActorPathSpec {

  
    @Test def `must support parsing its String rep`: Unit = {
      val path = RootActorPath(Address("akka.tcp", "mysys")) / "user"
      assertThat(ActorPath.fromString(path.toString), equalTo(path))
    }

    @Test def `must support parsing remote paths`: Unit = {
      val remote = "akka://sys@host:1234/some/ref"
      assertThat(ActorPath.fromString(remote).toString, equalTo(remote))
    }

    @Test def `must throw exception upon malformed paths`: Unit = {
      intercept[MalformedURLException] { ActorPath.fromString("") }
      intercept[MalformedURLException] { ActorPath.fromString("://hallo") }
      intercept[MalformedURLException] { ActorPath.fromString("s://dd@:12") }
      intercept[MalformedURLException] { ActorPath.fromString("s://dd@h:hd") }
      intercept[MalformedURLException] { ActorPath.fromString("a://l:1/b") }
    }

    @Test def `must create correct toString`: Unit = {
      val a = Address("akka.tcp", "mysys")
      assertThat(RootActorPath(a).toString, equalTo("akka.tcp://mysys/"))
      assertThat((RootActorPath(a) / "user").toString, equalTo("akka.tcp://mysys/user"))
      assertThat((RootActorPath(a) / "user" / "foo").toString, equalTo("akka.tcp://mysys/user/foo"))
      assertThat((RootActorPath(a) / "user" / "foo" / "bar").toString, equalTo("akka.tcp://mysys/user/foo/bar"))
    }

    @Test def `must have correct path elements`: Unit = {
      assertThat((RootActorPath(Address("akka.tcp", "mysys")) / "user" / "foo" / "bar").elements.toSeq, equalTo(Seq("user", "foo", "bar")))
    }

    @Test def `must create correct toStringWithAddress`: Unit = {
      val local = Address("akka.tcp", "mysys")
      val a = local.copy(host = Some("aaa"), port = Some(2552))
      val b = a.copy(host = Some("bb"))
      val c = a.copy(host = Some("cccc"))
      val root = RootActorPath(local)
      assertThat(root.toStringWithAddress(a), equalTo("akka.tcp://mysys@aaa:2552/"))
      assertThat((root / "user").toStringWithAddress(a), equalTo("akka.tcp://mysys@aaa:2552/user"))
      assertThat((root / "user" / "foo").toStringWithAddress(a), equalTo("akka.tcp://mysys@aaa:2552/user/foo"))

      assertThat(//      root.toStringWithAddress(b), equalTo("akka.tcp://mysys@bb:2552/"))
      assertThat((root / "user").toStringWithAddress(b), equalTo("akka.tcp://mysys@bb:2552/user"))
      assertThat((root / "user" / "foo").toStringWithAddress(b), equalTo("akka.tcp://mysys@bb:2552/user/foo"))

      assertThat(root.toStringWithAddress(c), equalTo("akka.tcp://mysys@cccc:2552/"))
      assertThat((root / "user").toStringWithAddress(c), equalTo("akka.tcp://mysys@cccc:2552/user"))
      assertThat((root / "user" / "foo").toStringWithAddress(c), equalTo("akka.tcp://mysys@cccc:2552/user/foo"))

      val rootA = RootActorPath(a)
      assertThat(rootA.toStringWithAddress(b), equalTo("akka.tcp://mysys@aaa:2552/"))
      assertThat((rootA / "user").toStringWithAddress(b), equalTo("akka.tcp://mysys@aaa:2552/user"))
      assertThat((rootA / "user" / "foo").toStringWithAddress(b), equalTo("akka.tcp://mysys@aaa:2552/user/foo"))

    }
  }