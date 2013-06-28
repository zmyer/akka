/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import language.postfixOps

import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import akka.routing._
import scala.concurrent.duration._

object DeployerSpec {
  val deployerConf = ConfigFactory.parseString("""
      akka.actor.deployment {
        /service1 {
        }
        /service-direct {
          router = from-code
        }
        /service-direct2 {
          router = from-code
          # nr-of-instances ignored when router = from-code
          nr-of-instances = 2
        }
        /service3 {
          dispatcher = my-dispatcher
        }
        /service4 {
          mailbox = my-mailbox
        }
        /service-round-robin {
          router = round-robin
        }
        /service-random {
          router = random
        }
        /service-scatter-gather {
          router = scatter-gather
          within = 2 seconds
        }
        /service-consistent-hashing {
          router = consistent-hashing
        }
        /service-resizer {
          router = round-robin
          resizer {
            lower-bound = 1
            upper-bound = 10
          }
        }
        /some/random-service {
          router = round-robin
        }
        "/some/*" {
          router = random
        }
        "/*/some" {
          router = scatter-gather
        }
      }
      """, ConfigParseOptions.defaults)

  class RecipeActor extends Actor {
    def receive = { case _ ⇒ }
  }

}

class DeployerSpec extends AkkaSpec(DeployerSpec.deployerConf) {
  
    @Test def `must be able to parse 'akka.actor.deployment._' with all default values`: Unit = {
      val service = "/service1"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))

      deployment must be(Some(
        Deploy(
          service,
          deployment.get.config,
          NoRouter,
          NoScopeGiven,
          Deploy.NoDispatcherGiven,
          Deploy.NoMailboxGiven)))
    }

    @Test def `must use None deployment for undefined service`: Unit = {
      val service = "/undefined"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      assertThat(deployment, equalTo(None))
    }

    @Test def `must be able to parse 'akka.actor.deployment._' with dispatcher config`: Unit = {
      val service = "/service3"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))

      deployment must be(Some(
        Deploy(
          service,
          deployment.get.config,
          NoRouter,
          NoScopeGiven,
          dispatcher = "my-dispatcher",
          Deploy.NoMailboxGiven)))
    }

    @Test def `must be able to parse 'akka.actor.deployment._' with mailbox config`: Unit = {
      val service = "/service4"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))

      deployment must be(Some(
        Deploy(
          service,
          deployment.get.config,
          NoRouter,
          NoScopeGiven,
          Deploy.NoDispatcherGiven,
          mailbox = "my-mailbox")))
    }

    @Test def `must detect invalid number-of-instances`: Unit = {
      intercept[com.typesafe.config.ConfigException.WrongType] {
        val invalidDeployerConf = ConfigFactory.parseString("""
            akka.actor.deployment {
              /service-invalid-number-of-instances {
                router = round-robin
                nr-of-instances = boom
              }
            }
            """, ConfigParseOptions.defaults).withFallback(AkkaSpec.testConf)

        shutdown(ActorSystem("invalid-number-of-instances", invalidDeployerConf))
      }
    }

    @Test def `must detect invalid deployment path`: Unit = {
      val e = intercept[InvalidActorNameException] {
        val invalidDeployerConf = ConfigFactory.parseString("""
            akka.actor.deployment {
              /gul/ubåt {
                router = round-robin
                nr-of-instances = 2
              }
            }
            """, ConfigParseOptions.defaults).withFallback(AkkaSpec.testConf)

        shutdown(ActorSystem("invalid-path", invalidDeployerConf))
      }
      e.getMessage must include("[ubåt]")
      e.getMessage must include("[/gul/ubåt]")
    }

    @Test def `must be able to parse 'akka.actor.deployment._' with from-code router`: Unit = {
      assertRouting("/service-direct", NoRouter, "/service-direct")
    }

    @Test def `must ignore nr-of-instances with from-code router`: Unit = {
      assertRouting("/service-direct2", NoRouter, "/service-direct2")
    }

    @Test def `must be able to parse 'akka.actor.deployment._' with round-robin router`: Unit = {
      assertRouting("/service-round-robin", RoundRobinRouter(1), "/service-round-robin")
    }

    @Test def `must be able to parse 'akka.actor.deployment._' with random router`: Unit = {
      assertRouting("/service-random", RandomRouter(1), "/service-random")
    }

    @Test def `must be able to parse 'akka.actor.deployment._' with scatter-gather router`: Unit = {
      assertRouting("/service-scatter-gather", ScatterGatherFirstCompletedRouter(nrOfInstances = 1, within = 2 seconds), "/service-scatter-gather")
    }

    @Test def `must be able to parse 'akka.actor.deployment._' with consistent-hashing router`: Unit = {
      assertRouting("/service-consistent-hashing", ConsistentHashingRouter(1), "/service-consistent-hashing")
    }

    @Test def `must be able to parse 'akka.actor.deployment._' with router resizer`: Unit = {
      val resizer = DefaultResizer()
      assertRouting("/service-resizer", RoundRobinRouter(resizer = Some(resizer)), "/service-resizer")
    }

    @Test def `must be able to use wildcards`: Unit = {
      assertRouting("/some/wildcardmatch", RandomRouter(1), "/some/*")
      assertRouting("/somewildcardmatch/some", ScatterGatherFirstCompletedRouter(nrOfInstances = 1, within = 2 seconds), "/*/some")
    }

    def assertRouting(service: String, expected: RouterConfig, expectPath: String): Unit = {
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      assertThat(deployment.map(_.path).getOrElse("NOT FOUND"), equalTo(expectPath))
      assertThat(deployment.get.routerConfig.getClass, equalTo(expected.getClass))
      assertThat(deployment.get.routerConfig.resizer, equalTo(expected.resizer))
      assertThat(deployment.get.scope, equalTo(NoScopeGiven))
    }
  }