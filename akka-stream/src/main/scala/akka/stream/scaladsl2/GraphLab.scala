package akka.stream.scaladsl2

import akka.stream.Transformer
import akka.actor.ActorSystem

// FIXME only for playing around
object GraphLab extends App {

  implicit val system = ActorSystem("GraphLab")
  implicit val materializer = FlowMaterializer()

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

  FlowGraph { b ⇒
    val merge1 = b.merge[String]
    b.
      addEdge(in1, f1, merge1).
      addEdge(in2, f2, merge1).
      addEdge(merge1, f3, out1)
  }.run(materializer)

  FlowGraph { b ⇒
    val bcast2 = b.broadcast[String]
    b.
      addEdge(in1, f1, bcast2).
      addEdge(bcast2, f2, out1).
      addEdge(bcast2, f3, out2)
  }.run(materializer)

  FlowGraph { b ⇒
    val merge3 = b.merge[String]
    val bcast3 = b.broadcast[String]
    b.
      addEdge(in1, f1, merge3).
      addEdge(in2, f2, merge3).
      addEdge(merge3, f3, bcast3).
      addEdge(bcast3, f4, out1).
      addEdge(bcast3, f5, out2)
    b.build()
  }.run(materializer)

  /**
   * in ---> f1 -+-> f2 -+-> f3 ---> out1
   *             ^       |
   *             |       V
   *             f5 <-+- f4
   *                  |
   *                  V
   *                  f6 ---> out2
   */

  try {
    FlowGraph { b ⇒
      val merge4 = b.merge[String]
      val bcast41 = b.broadcast[String]
      val bcast42 = b.broadcast[String]
      b.
        addEdge(in1, f1, merge4).
        addEdge(merge4, f2, bcast41).
        addEdge(bcast41, f3, out1).
        addEdge(bcast41, f4, bcast42).
        addEdge(bcast42, f5, merge4). // cycle
        addEdge(bcast42, f6, out2)
    }.run(materializer)
  } catch {
    case e: IllegalArgumentException ⇒ println("Expected: " + e.getMessage)
  }

  FlowGraph { b ⇒
    val merge5 = b.merge[Fruit]
    val in3 = IterableSource(List.empty[Apple])
    val in4 = IterableSource(List.empty[Orange])
    val f7 = FlowFrom[Fruit].transform("f7", op[Fruit, Fruit])
    val f8 = FlowFrom[Fruit].transform("f8", op[Fruit, String])
    b.
      addEdge(in3, f7, merge5).
      addEdge(in4, f7, merge5).
      addEdge(merge5, f8, out1)
  }

  val mg = FlowGraph { b ⇒
    val merge6 = b.merge[String]
    b.
      addEdge(f1, merge6).
      addEdge(f2, merge6).
      addEdge(merge6, f3)

    b.attachSource(f1, in1)
    b.attachSource(f2, in2)
    b.attachSink(f3, out1)

  }.run(materializer)
  assert(out1.publisher(mg) != null)

  FlowGraph { implicit b ⇒
    import FlowGraphBuilderImplicits._
    val merge = b.merge[String]
    val bcast = b.broadcast[String]
    in1 ~> f1 ~> merge ~> f2 ~> bcast ~> f3 ~> out1
    in2 ~> f4 ~> merge
    bcast ~> f5 ~> out2
  }.run(materializer)

  val partial1 = PartialFlowGraph { implicit b ⇒
    import FlowGraphBuilderImplicits._
    val merge = b.merge[String]
    val bcast = b.broadcast[String]
    undefinedSource ~> f1 ~> merge ~> f2 ~> bcast ~> f3 ~> undefinedSink[String]
    undefinedSource ~> f4 ~> merge
    bcast ~> f5 ~> undefinedSink[String]
  }
  println("# partial1 flowsWithoutSource: " + partial1.flowsWithoutSource)
  println("# partial1 flowsWithoutSink: " + partial1.flowsWithoutSink)

  val partial2 = PartialFlowGraph(partial1) { implicit b ⇒
    import FlowGraphBuilderImplicits._
    in1 ~=> f1
    in2 ~=> f4
  }
  println("# partial2 flowsWithoutSource: " + partial2.flowsWithoutSource)
  println("# partial2 flowsWithoutSink: " + partial2.flowsWithoutSink)

  FlowGraph(partial2) { implicit b ⇒
    import FlowGraphBuilderImplicits._
    f3 ~=> out1
    f5 ~=> out2
  }.run(materializer)

}

trait Fruit
class Apple extends Fruit
class Orange extends Fruit
