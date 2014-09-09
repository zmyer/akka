package akka.stream.scaladsl2

import akka.stream.testkit.AkkaSpec

import akka.stream.{ OverflowStrategy, MaterializerSettings }
import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.stream.scaladsl2.FlowGraphImplicits._

class GraphOpsIntegrationSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  "FlowGraphs" must {

    "support broadcast - merge layouts" in {
      val resultFuture = FutureSink[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val bcast = Broadcast[Int]("broadcast")
        val merge = Merge[Int]("merge")

        FlowFrom(List(1, 2, 3)) ~> bcast
        bcast ~> FlowFrom[Int] ~> merge
        bcast ~> FlowFrom[Int].map(_ + 3) ~> merge
        merge ~> FlowFrom[Int].grouped(10) ~> resultFuture
      }.run()

      Await.result(g.getSinkFor(resultFuture), 3.seconds).sorted should be(List(1, 2, 3, 4, 5, 6))
    }

    "support wikipedia Topological_sorting" in {
      // see https://en.wikipedia.org/wiki/Topological_sorting#mediaviewer/File:Directed_acyclic_graph.png
      val resultFuture2 = FutureSink[Seq[Int]]
      val resultFuture9 = FutureSink[Seq[Int]]
      val resultFuture10 = FutureSink[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val b3 = Broadcast[Int]
        val b7 = Broadcast[Int]
        val b11 = Broadcast[Int]
        val m8 = Merge[Int]
        val m9 = Merge[Int]
        val m10 = Merge[Int]
        val m11 = Merge[Int]
        val in3 = IterableSource(List(3))
        val in5 = IterableSource(List(5))
        val in7 = IterableSource(List(7))

        val f = FlowFrom[Int]

        in7 ~> f ~> b7 ~> f ~> m11 ~> f ~> b11
        b11 ~> f ~> m9
        b7 ~> f ~> m8 ~> f ~> m9
        b11 ~> f ~> m10
        in5 ~> f ~> m11
        in3 ~> f ~> b3 ~> f ~> m8
        b3 ~> f ~> m10

        m9 ~> f.grouped(1000) ~> resultFuture9
        m10 ~> f.grouped(1000) ~> resultFuture10
        b11 ~> f.grouped(1000) ~> resultFuture2 // there is no vertex 2 because it has only one in and out edges

      }.run()

      Await.result(g.getSinkFor(resultFuture2), 3.seconds).sorted should be(List(5, 7))
      Await.result(g.getSinkFor(resultFuture9), 3.seconds).sorted should be(List(3, 5, 7, 7))
      Await.result(g.getSinkFor(resultFuture10), 3.seconds).sorted should be(List(3, 5, 7))

    }
  }

}
