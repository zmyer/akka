/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.dispatch

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.reflect.ClassTag

import com.typesafe.config.ConfigFactory

import akka.ConfigurationException
import akka.actor.{ Actor, ActorRef, Props }
import akka.dispatch.{ BalancingDispatcher, Dispatcher, Dispatchers, MessageDispatcher, PinnedDispatcher }
import akka.testkit.{ AkkaSpec, ImplicitSender }

object DispatchersSpec {
  val config = """
    myapp {
      mydispatcher {
        throughput = 17
      }
      thread-pool-dispatcher {
        executor = thread-pool-executor
      }
      my-pinned-dispatcher {
        executor = thread-pool-executor
        type = PinnedDispatcher
      }
      balancing-dispatcher {
        type = BalancingDispatcher
      }
    }
    akka.actor.deployment {
      /echo1 {
        dispatcher = myapp.mydispatcher
      }
      /echo2 {
        dispatcher = myapp.mydispatcher
      }
     }
    """

  class ThreadNameEcho extends Actor {
    def receive = {
      case _ ⇒ sender ! Thread.currentThread.getName
    }
  }
}

class DispatchersSpec extends AkkaSpec(DispatchersSpec.config) with ImplicitSender {
  import DispatchersSpec._
  val df = system.dispatchers
  import df._

  val tipe = "type"
  val keepalivems = "keep-alive-time"
  val corepoolsizefactor = "core-pool-size-factor"
  val maxpoolsizefactor = "max-pool-size-factor"
  val allowcoretimeout = "allow-core-timeout"
  val throughput = "throughput"
  val id = "id"

  def instance(dispatcher: MessageDispatcher): (MessageDispatcher) ⇒ Boolean = _ == dispatcher
  def ofType[T <: MessageDispatcher: ClassTag]: (MessageDispatcher) ⇒ Boolean = _.getClass == implicitly[ClassTag[T]].runtimeClass

  def typesAndValidators: Map[String, (MessageDispatcher) ⇒ Boolean] = Map(
    "BalancingDispatcher" -> ofType[BalancingDispatcher],
    "PinnedDispatcher" -> ofType[PinnedDispatcher],
    "Dispatcher" -> ofType[Dispatcher])

  def validTypes = typesAndValidators.keys.toList

  val defaultDispatcherConfig = settings.config.getConfig("akka.actor.default-dispatcher")

  lazy val allDispatchers: Map[String, MessageDispatcher] = {
    validTypes.map(t ⇒ (t, from(ConfigFactory.parseMap(Map(tipe -> t, id -> t).asJava).
      withFallback(defaultDispatcherConfig)))).toMap
  }

  def assertMyDispatcherIsUsed(actor: ActorRef): Unit = {
    actor ! "what's the name?"
    val Expected = "(DispatchersSpec-myapp.mydispatcher-[1-9][0-9]*)".r
    expectMsgPF(remaining) {
      case Expected(x) ⇒
    }
  }

  
    @Test def `must use defined properties`: Unit = {
      val dispatcher = lookup("myapp.mydispatcher")
      assertThat(dispatcher.throughput, equalTo(17))
    }

    @Test def `must use specific id`: Unit = {
      val dispatcher = lookup("myapp.mydispatcher")
      assertThat(dispatcher.id, equalTo("myapp.mydispatcher"))
    }

    @Test def `must complain about missing config`: Unit = {
      intercept[ConfigurationException] {
        lookup("myapp.other-dispatcher")
      }
    }

    @Test def `must have only one default dispatcher`: Unit = {
      val dispatcher = lookup(Dispatchers.DefaultDispatcherId)
      assertThat(dispatcher, equalTo(defaultGlobalDispatcher))
      dispatcher must be === system.dispatcher
    }

    @Test def `must throw ConfigurationException if type does not exist`: Unit = {
      intercept[ConfigurationException] {
        from(ConfigFactory.parseMap(Map(tipe -> "typedoesntexist", id -> "invalid-dispatcher").asJava).
          withFallback(defaultDispatcherConfig))
      }
    }

    @Test def `must get the correct types of dispatchers`: Unit = {
      //All created/obtained dispatchers are of the expeced type/instance
      assert(typesAndValidators.forall(tuple ⇒ tuple._2(allDispatchers(tuple._1))))
    }

    @Test def `must provide lookup of dispatchers by id`: Unit = {
      val d1 = lookup("myapp.mydispatcher")
      val d2 = lookup("myapp.mydispatcher")
      assertThat(d1, equalTo(d2))
    }

    @Test def `must include system name and dispatcher id in thread names for fork-join-executor`: Unit = {
      assertMyDispatcherIsUsed(system.actorOf(Props[ThreadNameEcho].withDispatcher("myapp.mydispatcher")))
    }

    @Test def `must include system name and dispatcher id in thread names for thread-pool-executor`: Unit = {
      system.actorOf(Props[ThreadNameEcho].withDispatcher("myapp.thread-pool-dispatcher")) ! "what's the name?"
      val Expected = "(DispatchersSpec-myapp.thread-pool-dispatcher-[1-9][0-9]*)".r
      expectMsgPF(remaining) {
        case Expected(x) ⇒
      }
    }

    @Test def `must include system name and dispatcher id in thread names for default-dispatcher`: Unit = {
      system.actorOf(Props[ThreadNameEcho]) ! "what's the name?"
      val Expected = "(DispatchersSpec-akka.actor.default-dispatcher-[1-9][0-9]*)".r
      expectMsgPF(remaining) {
        case Expected(x) ⇒
      }
    }

    @Test def `must include system name and dispatcher id in thread names for pinned dispatcher`: Unit = {
      system.actorOf(Props[ThreadNameEcho].withDispatcher("myapp.my-pinned-dispatcher")) ! "what's the name?"
      val Expected = "(DispatchersSpec-myapp.my-pinned-dispatcher-[1-9][0-9]*)".r
      expectMsgPF(remaining) {
        case Expected(x) ⇒
      }
    }

    @Test def `must include system name and dispatcher id in thread names for balancing dispatcher`: Unit = {
      system.actorOf(Props[ThreadNameEcho].withDispatcher("myapp.balancing-dispatcher")) ! "what's the name?"
      val Expected = "(DispatchersSpec-myapp.balancing-dispatcher-[1-9][0-9]*)".r
      expectMsgPF(remaining) {
        case Expected(x) ⇒
      }
    }

    @Test def `must use dispatcher in deployment config`: Unit = {
      assertMyDispatcherIsUsed(system.actorOf(Props[ThreadNameEcho], name = "echo1"))
    }

    @Test def `must use dispatcher in deployment config, trumps code`: Unit = {
      assertMyDispatcherIsUsed(
        system.actorOf(Props[ThreadNameEcho].withDispatcher("myapp.my-pinned-dispatcher"), name = "echo2"))
    }

  }