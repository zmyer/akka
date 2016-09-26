/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.ddata

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorSystem
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ddata.Replicator.Changed
import akka.cluster.ddata.Replicator.GetKeyIds
import akka.cluster.ddata.Replicator.GetKeyIdsResult
import akka.cluster.ddata.Replicator.Subscribe
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateResponse
import akka.cluster.ddata.Replicator.WriteLocal
import com.typesafe.config.ConfigFactory

/**
 * This "sample" simulates lots of data entries, and can be used for
 * optimizing replication (e.g. catch-up when adding more nodes).
 */
object LotsOfDataBot2 {

  def main(args: Array[String]): Unit = {
    if (args.isEmpty)
      startup(Seq("2551", "2552", "0"))
    else
      startup(args)
  }

  def startup(ports: Seq[String]): Unit = {
    ports.foreach { port ⇒
      // Override the configuration of the port
      val config = ConfigFactory.parseString("akka.remote.artery.canonical.port=" + port).
        withFallback(ConfigFactory.load(
          ConfigFactory.parseString("""
            passive = off
            max-entries = 200000
            akka.actor.provider = cluster

            akka.actor.default-mailbox {
              mailbox-type = akka.remote.artery.LoggingMailboxType
              size-limit = 20
            }

            akka.remote.artery {
              enabled = on
              canonical.hostname = 127.0.0.1
              canonical.port = 0
              advanced.use-control-stream-dispatcher = control-dispatcher
            }

            akka.cluster {
              seed-nodes = [
                "akka://ClusterSystem@127.0.0.1:2551",
                "akka://ClusterSystem@127.0.0.1:2552"]

              auto-down-unreachable-after = 60s

              failure-detector = ${collecting-failure-detector}
              failure-detector.tag = cluster
            }
            akka.remote.artery.advanced.maximum-frame-size = 1 MiB
            akka.cluster.distributed-data.max-delta-elements = 500
            akka.cluster.distributed-data.gossip-interval = 1s

            collecting-failure-detector = {
              implementation-class = "akka.remote.artery.CollectorFailureDetector"
              sliding-window-size = 16
              heartbeat-interval = 1 s
              acceptable-heartbeat-pause = 10 s
            }

            control-dispatcher {
              type = Dispatcher
              executor = "thread-pool-executor"
              thread-pool-executor {
                fixed-pool-size = 2
              }
              throughput = 1
            }
            """)))

      // Create an Akka system
      val system = ActorSystem("ClusterSystem", config)
      // Create an actor that handles cluster domain events
      system.actorOf(Props[LotsOfDataBot2], name = "dataBot")
    }
  }

  private case object Tick

}

class LotsOfDataBot2 extends Actor with ActorLogging {
  import LotsOfDataBot2._
  import Replicator._

  val replicator = DistributedData(context.system).replicator
  implicit val node = Cluster(context.system)

  import context.dispatcher
  val isPassive = context.system.settings.config.getBoolean("passive")
  var tickTask =
    if (isPassive)
      context.system.scheduler.schedule(1.seconds, 1.seconds, self, Tick)
    else
      context.system.scheduler.schedule(20.millis, 20.millis, self, Tick)

  val startTime = System.nanoTime()
  var count = 1L
  val maxEntries = context.system.settings.config.getInt("max-entries")
  val maxTopEntries = 50000

  def receive = if (isPassive) passive else active

  def active: Receive = {
    case Tick ⇒
      val loop = if (count >= maxEntries) 1 else 100
      for (_ ← 1 to loop) {
        count += 1
        if (count % 1000 == 0)
          log.info("Reached {} entries", count)
        if (count == maxEntries) {
          log.info("Reached {} entries", count)
          tickTask.cancel()
          tickTask = context.system.scheduler.schedule(1.seconds, 1.seconds, self, Tick)
        }
        val fullKey = (count % maxEntries).toString
        // ORMap
        val topLevelKey = ORMapKey[PNCounter]((fullKey.hashCode % maxTopEntries).toString)
        if (count <= 100)
          replicator ! Subscribe(topLevelKey, self)
        val update = Update(topLevelKey, ORMap.empty[PNCounter], WriteLocal) { topLevelMap ⇒
          topLevelMap.updated(node, fullKey, PNCounter.empty)(_ + 1)
        }
        replicator ! update
      }

    case _: UpdateResponse[_] ⇒ // ignore

    case c @ Changed(key: ORMapKey[PNCounter]) ⇒
      val topLevelMap = c.get(key)
      val entries = topLevelMap.entries.map { case (k, counter) ⇒ k → counter.value }
      log.info("Currently {} entries in top level key {}: {}", entries.size, key.id, entries.mkString(", "))
  }

  def passive: Receive = {
    case Tick ⇒
      if (!tickTask.isCancelled)
        replicator ! GetKeyIds
    case GetKeyIdsResult(keys) ⇒
      if (keys.size >= maxTopEntries) {
        tickTask.cancel()
        val duration = (System.nanoTime() - startTime).nanos.toMillis
        log.info("It took {} ms to replicate {} entries", duration, keys.size)
      } else {
        log.info("Loaded {} top level entries", keys.size)
      }
  }

  override def postStop(): Unit = tickTask.cancel()

}

