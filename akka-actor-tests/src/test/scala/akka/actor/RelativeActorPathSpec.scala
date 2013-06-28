/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import java.net.URLEncoder
import scala.collection.immutable

class RelativeActorPathSpec {

  def elements(path: String): immutable.Seq[String] = RelativeActorPath.unapply(path).getOrElse(Nil)

      @Test def `must match single name`: Unit = {
      assertThat(elements("foo"), equalTo(List("foo")))
    }
    @Test def `must match path separated names`: Unit = {
      assertThat(elements("foo/bar/baz"), equalTo(List("foo", "bar", "baz")))
    }
    @Test def `must match url encoded name`: Unit = {
      val name = URLEncoder.encode("akka://ClusterSystem@127.0.0.1:2552", "UTF-8")
      assertThat(elements(name), equalTo(List(name)))
    }
    @Test def `must match path with uid fragment`: Unit = {
      assertThat(elements("foo/bar/baz#1234"), equalTo(List("foo", "bar", "baz#1234")))
    }
  }