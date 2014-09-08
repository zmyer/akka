/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.testkit.AkkaSpec
import akka.stream.Transformer

class FlowGraphCompileSpec extends AkkaSpec {

  implicit val mat = FlowMaterializer()

  def op[In, Out]: () ⇒ Transformer[In, Out] = { () ⇒
    new Transformer[In, Out] {
      override def onNext(elem: In) = List(elem.asInstanceOf[Out])
    }
  }

  val f1 = FlowFrom[String].transform("f1", op[String, String])
  val f2 = FlowFrom[String].transform("f2", op[String, String])
  val f3 = FlowFrom[String].transform("f3", op[String, String])
  val f4 = FlowFrom[String].transform("f4", op[String, String])
  val f5 = FlowFrom[String].transform("f5", op[String, String])
  val f6 = FlowFrom[String].transform("f6", op[String, String])

  val in1 = IterableSource(List("a", "b", "c"))
  val in2 = IterableSource(List("d", "e", "f"))
  val out1 = PublisherSink[String]
  val out2 = FutureSink[String]

  "FlowGraph" should {
    "build simple merge" in {
      FlowGraph { b ⇒
        val merge = Merge[String]
        b.
          addEdge(in1, f1, merge).
          addEdge(in2, f2, merge).
          addEdge(merge, f3, out1)
      }
    }

    "build simple broadcast" in {
      FlowGraph { b ⇒
        val bcast = Broadcast[String]
        b.
          addEdge(in1, f1, bcast).
          addEdge(bcast, f2, out1).
          addEdge(bcast, f3, out2)
      }
    }

    "build simple merge - broadcast" in {
      FlowGraph { b ⇒
        val merge = Merge[String]
        val bcast = Broadcast[String]
        b.
          addEdge(in1, f1, merge).
          addEdge(in2, f2, merge).
          addEdge(merge, f3, bcast).
          addEdge(bcast, f4, out1).
          addEdge(bcast, f5, out2)
      }
    }

    "build simple merge - broadcast with implicits" in {
      FlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        val merge = Merge[String]
        val bcast = Broadcast[String]
        in1 ~> f1 ~> merge ~> f2 ~> bcast ~> f3 ~> out1
        in2 ~> f4 ~> merge
        bcast ~> f5 ~> out2
      }
    }

    /**
     * in ---> f1 -+-> f2 -+-> f3 ---> out1
     *             ^       |
     *             |       V
     *             f5 <-+- f4
     *                  |
     *                  V
     *                  f6 ---> out2
     */
    "detect cycle in " in {
      intercept[IllegalArgumentException] {
        FlowGraph { b ⇒
          val merge = Merge[String]
          val bcast1 = Broadcast[String]
          val bcast2 = Broadcast[String]
          b.
            addEdge(in1, f1, merge).
            addEdge(merge, f2, bcast1).
            addEdge(bcast1, f3, out1).
            addEdge(bcast1, f4, bcast2).
            addEdge(bcast2, f5, merge). // cycle
            addEdge(bcast2, f6, out2)
        }
      }.getMessage.toLowerCase should include("cycle")

    }

    "express complex topologies in a readable way" in {
      intercept[IllegalArgumentException] {
        FlowGraph { implicit b ⇒
          val merge = Merge[String]
          val bcast1 = Broadcast[String]
          val bcast2 = Broadcast[String]
          import FlowGraphImplicits._
          in1 ~> f1 ~> merge ~> f2 ~> bcast1 ~> f3 ~> out1
          bcast1 ~> f4 ~> bcast2 ~> f5 ~> merge
          bcast2 ~> f6 ~> out2
        }
      }.getMessage.toLowerCase should include("cycle")
    }

    "attachSource and attachSink" in {
      val mg = FlowGraph { b ⇒
        val merge = Merge[String]
        b.
          addEdge(f1, merge).
          addEdge(f2, merge).
          addEdge(merge, f3)

        b.attachSource(f1, in1)
        b.attachSource(f2, in2)
        b.attachSink(f3, out1)

      }.run()
      out1.publisher(mg) should not be (null)
    }

    "build partial flow graphs" in {
      val partial1 = PartialFlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        val merge = Merge[String]
        val bcast = Broadcast[String]
        undefinedSource ~> f1 ~> merge ~> f2 ~> bcast ~> f3 ~> undefinedSink[String]
        undefinedSource ~> f4 ~> merge
        bcast ~> f5 ~> undefinedSink[String]
      }
      partial1.flowsWithoutSource should be(Set(f1, f4))
      partial1.flowsWithoutSink should be(Set(f3, f5))

      val partial2 = PartialFlowGraph(partial1) { implicit b ⇒
        b.attachSource(f1, in1)
        b.attachSource(f4, in2)
      }
      partial2.flowsWithoutSource should be(Set.empty)
      partial2.flowsWithoutSink should be(Set(f3, f5))

      FlowGraph(partial2) { implicit b ⇒
        b.attachSink(f3, out1)
        b.attachSink(f5, out2)
      }
    }

  }
}