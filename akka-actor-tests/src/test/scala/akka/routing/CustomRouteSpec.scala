/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.testkit.AkkaSpec

class CustomRouteSpec extends AkkaSpec {

  //#custom-router
  import akka.actor.{ ActorRef, Props, SupervisorStrategy }
  import akka.dispatch.Dispatchers

  class MyRouter(target: ActorRef) extends RouterConfig {
    override def createRoute(provider: RouteeProvider): Route = {
      provider.createRoutees(1)

      {
        case (sender, message: String) ⇒ List(Destination(sender, target))
        case (sender, message)         ⇒ toAll(sender, provider.routees)
      }
    }
    override def supervisorStrategy = SupervisorStrategy.defaultStrategy
    override def routerDispatcher = Dispatchers.DefaultDispatcherId
  }
  //#custom-router

  
    @Test def `must be testable`: Unit = {
      //#test-route
      import akka.pattern.ask
      import akka.testkit.ExtractRoute
      import scala.concurrent.Await
      import scala.concurrent.duration._

      val target = system.actorOf(Props.empty)
      val router = system.actorOf(Props.empty.withRouter(new MyRouter(target)))
      val route = ExtractRoute(router)
      val r = Await.result(router.ask(CurrentRoutees)(1 second).
        mapTo[RouterRoutees], 1 second)
      assertThat(r.routees.size, equalTo(1))
      assertThat(route(testActor -> "hallo"), equalTo(List(Destination(testActor, target))))
      assertThat(route(testActor -> 12), equalTo(List(Destination(testActor, r.routees.head))))
      //#test-route
    }

  }