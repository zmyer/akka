/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.language.existentials
import scalax.collection.edge.LDiEdge
import scalax.collection.mutable.Graph
import scalax.collection.immutable.{ Graph => ImmutableGraph }
import org.reactivestreams.Subscriber
import akka.stream.impl.BlackholeSubscriber
import org.reactivestreams.Publisher
import org.reactivestreams.Processor

class Merge[T] extends FanInOperation[T] {
  override def toString = "merge"
}
class Broadcast[T] extends FanOutOperation[T] {
  override def toString = "broadcast"
}

trait FanOutOperation[T] extends FanOperation[T]
trait FanInOperation[T] extends FanOperation[T]
sealed trait FanOperation[T]

/**
 * INTERNAL API
 */
private[akka] object FlowGraphInternal {

  sealed trait Vertex
  case class SourceVertex(source: Source[_]) extends Vertex {
    override def toString = source.toString
  }
  case class SinkVertex(sink: Sink[_]) extends Vertex {
    override def toString = sink.toString
  }
  case class FanOperationVertex(op: FanOperation[_]) extends Vertex {
    override def toString = op.toString
  }
  object UndefinedSink {
    def apply(): UndefinedSink = new UndefinedSink
  }
  class UndefinedSink extends Vertex {
    override def toString = "UndefinedSink"
  }
  object UndefinedSource {
    def apply(): UndefinedSource = new UndefinedSource
  }
  class UndefinedSource extends Vertex {
    override def toString = "UndefinedSource"
  }

}

class FlowGraphBuilder private (graph: Graph[FlowGraphInternal.Vertex, LDiEdge]) {
  import FlowGraphInternal._

  private[akka] def this() = this(Graph.empty[FlowGraphInternal.Vertex, LDiEdge])

  private[akka] def this(immutableGraph: ImmutableGraph[FlowGraphInternal.Vertex, LDiEdge]) =
    this(Graph.from(edges = immutableGraph.edges.map(e => LDiEdge(e.from.value, e.to.value)(e.label)).toIterable))

  implicit val edgeFactory = scalax.collection.edge.LkDiEdge

  // FIXME do we need these?
  def merge[T] = new Merge[T]
  def broadcast[T] = new Broadcast[T]

  def addEdge[In, Out](source: Source[In], flow: ProcessorFlow[In, Out], sink: FanOperation[Out]): this.type = {
    // FIXME sourcePrecondition
    checkFanPrecondition(sink, in = true)
    graph.addLEdge(SourceVertex(source), FanOperationVertex(sink))(flow)
    this
  }

  def addEdge[In, Out](source: FanOperation[In], flow: ProcessorFlow[In, Out], sink: Sink[Out]): this.type = {
    checkFanPrecondition(source, in = false)
    // FIXME sinkPrecondition
    graph.addLEdge(FanOperationVertex(source), SinkVertex(sink))(flow)
    this
  }

  def addEdge[In, Out](source: FanOperation[In], flow: ProcessorFlow[In, Out], sink: FanOperation[Out]): this.type = {
    checkFanPrecondition(source, in = false)
    checkFanPrecondition(sink, in = true)
    graph.addLEdge(FanOperationVertex(source), FanOperationVertex(sink))(flow)
    this
  }

  def addEdge[In, Out](source: FanOperation[In], flow: ProcessorFlow[In, Out]): this.type = {
    checkFanPrecondition(source, in = false)
    graph.addLEdge(FanOperationVertex(source), UndefinedSink())(flow)
    this
  }

  def addEdge[In, Out](flow: ProcessorFlow[In, Out], sink: FanOperation[Out]): this.type = {
    checkFanPrecondition(sink, in = true)
    graph.addLEdge(UndefinedSource(), FanOperationVertex(sink))(flow)
    this
  }

  def addEdge[In, Out](flow: FlowWithSource[In, Out], sink: FanOperation[Out]): this.type = {
    addEdge(flow.input, flow.withoutSource, sink)
    this
  }

  def addEdge[In, Out](source: FanOperation[In], flow: FlowWithSink[In, Out]): this.type = {
    addEdge(source, flow.withoutSink, flow.output)
    this
  }

  def attachSink[Out](flow: HasNoSink[Out], sink: Sink[Out]): this.type = {
    // we can't use LkDiEdge becase the flow may exist several times in the graph
    val replaceEdges = graph.edges.filter(_.label == flow)
    require(replaceEdges.nonEmpty, s"No matching flow [${flow}]")
    replaceEdges.foreach { edge =>
      require(edge.to.value.isInstanceOf[UndefinedSink], s"Flow already attached to a sink [${edge.to.value}]")
      graph.remove(edge.to.value)
      graph.addLEdge(edge.from.value, SinkVertex(sink))(flow)
    }
    this
  }

  def attachSource[In](flow: HasNoSource[In], source: Source[In]): this.type = {
    // we can't use LkDiEdge becase the flow may exist several times in the graph
    val replaceEdges = graph.edges.filter(_.label == flow)
    require(replaceEdges.nonEmpty, s"No matching flow [${flow}]")
    replaceEdges.foreach { edge =>
      require(edge.from.value.isInstanceOf[UndefinedSource], s"Flow already attached to a source [${edge.from.value}]")
      graph.remove(edge.from.value)
      graph.addLEdge(SourceVertex(source), edge.to.value)(flow)
    }
    this
  }

  private def checkFanPrecondition(fan: FanOperation[_], in: Boolean): Unit = {
    fan match {
      case _: FanOutOperation[_] if in =>
        graph.find(FanOperationVertex(fan)) match {
          case Some(existing) if existing.incoming.nonEmpty =>
            throw new IllegalArgumentException(s"Fan-out [$fan] is already attached to input [${existing.incoming.head}]")
          case _ => // ok
        }
      case _: FanInOperation[_] if !in =>
        graph.find(FanOperationVertex(fan)) match {
          case Some(existing) if existing.outgoing.nonEmpty =>
            throw new IllegalArgumentException(s"Fan-in [$fan] is already attached to output [${existing.outgoing.head}]")
          case _ => // ok
        }
      case _ => // ok
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] def build(): FlowGraph = {
    checkPartialBuildPreconditions()
    checkBuildPreconditions()
    new FlowGraph(immutableGraph())
  }

  /**
   * INTERNAL API
   */
  private[akka] def partialBuild(): PartialFlowGraph = {
    checkPartialBuildPreconditions()
    new PartialFlowGraph(immutableGraph())
  }

  //convert it to an immutable.Graph
  private def immutableGraph(): ImmutableGraph[Vertex, LDiEdge] =
    ImmutableGraph.from(edges = graph.edges.map(e => LDiEdge(e.from.value, e.to.value)(e.label)).toIterable)

  private def checkPartialBuildPreconditions(): Unit = {
    graph.nodes.foreach { n ⇒ println(s"node ${n} has:\n    successors: ${n.diSuccessors}\n    predecessors${n.diPredecessors}\n    edges ${n.edges}") }

    graph.findCycle match {
      case None        ⇒
      case Some(cycle) ⇒ throw new IllegalArgumentException("Cycle detected, not supported yet. " + cycle)
    }
  }

  private def checkBuildPreconditions(): Unit = {
    val undefinedSourcesSinks = graph.nodes.filter {
      _.value match {
        case _: UndefinedSource | _: UndefinedSink ⇒ true
        case x                                     ⇒ false
      }
    }
    if (undefinedSourcesSinks.nonEmpty) {
      val formatted = undefinedSourcesSinks.map(n => n.value match {
        case u: UndefinedSource => s"$u -> ${n.outgoing.head.label} -> ${n.outgoing.head.to}"
        case u: UndefinedSink   => s"${n.incoming.head.from} -> ${n.incoming.head.label} -> $u"
      })
      throw new IllegalArgumentException("Undefined sources or sinks: " + formatted.mkString(", "))
    }
  }

}

object FlowGraph {
  def apply(block: FlowGraphBuilder => Unit): FlowGraph = {
    val builder = new FlowGraphBuilder
    block(builder)
    builder.build()
  }

  def apply(partialFlowGraph: PartialFlowGraph)(block: FlowGraphBuilder => Unit): FlowGraph = {
    val builder = new FlowGraphBuilder(partialFlowGraph.graph)
    block(builder)
    builder.build()
  }
}

class FlowGraph private[akka] (graph: ImmutableGraph[FlowGraphInternal.Vertex, LDiEdge]) {
  import FlowGraphInternal._
  def run(implicit materializer: FlowMaterializer): Unit = {
    println("# RUN ----------------")

    // start with sinks
    val startingNodes = graph.nodes.filter(_.diSuccessors.isEmpty)

    def dummySubscriber(name: String): Subscriber[Any] = new BlackholeSubscriber[Any](1) {
      override def toString = name
    }
    def dummyPublisher(name: String): Publisher[Any] = new Publisher[Any] {
      def subscribe(subscriber: Subscriber[Any]): Unit = subscriber.onComplete()
      override def toString = name
    }

    println("Starting nodes: " + startingNodes)
    var sources = Map.empty[Source[_], Subscriber[_]]

    var broadcasts = Map.empty[Any, (Subscriber[Any], Publisher[Any])]

    def traverse(edge: graph.EdgeT, downstreamSubscriber: Subscriber[Any]): Unit = {
      edge._1.value match {
        case SourceVertex(src) ⇒
          println("# source: " + src)
          sources += (src -> downstreamSubscriber)

        case FanOperationVertex(from: Merge[_]) ⇒
          println("# merge")
          require(edge._1.incoming.size == 2) // FIXME
          // FIXME materialize Merge and attach its output Publisher to the downstreamSubscriber
          val downstreamSub1 = dummySubscriber("subscriber1-" + edge._1.value)
          val downstreamSub2 = dummySubscriber("subscriber2-" + edge._1.value)
          traverse(edge._1.incoming.head, downstreamSub1)
          traverse(edge._1.incoming.tail.head, downstreamSub2)

        case FanOperationVertex(from: Broadcast[_]) ⇒
          require(edge._1.incoming.size == 1) // FIXME
          require(edge._1.outgoing.size == 2) // FIXME
          broadcasts.get(from) match {
            case Some((sub, pub)) ⇒
              println("# broadcast second")
              // already materialized
              pub.subscribe(downstreamSubscriber)
            case None ⇒
              println("# broadcast first")
              // FIXME materialize Broadcast and attach its output Publisher to the downstreamSubscriber
              val pub = dummyPublisher("publisher-" + edge._1.value)
              val sub = dummySubscriber("subscriber-" + edge._1.value)
              broadcasts += (from -> ((sub, pub)))
              pub.subscribe(downstreamSubscriber)
              traverse(edge._1.incoming.head, sub)
          }

        case other => throw new IllegalArgumentException("Unknown vertex: " + other)

      }

    }

    startingNodes.foreach { n ⇒
      n.value match {
        case SinkVertex(sink) ⇒
          require(n.incoming.size == 1) // FIXME
          val edge = n.incoming.head
          val flow = edge.label.asInstanceOf[ProcessorFlow[Any, Any]]
          println("# starting at sink: " + sink + " flow: " + flow)
          val f = flow.withSink(sink.asInstanceOf[Sink[Any]])
          val downstreamSubscriber = f.toSubscriber()
          traverse(edge, downstreamSubscriber)
        case other => throw new IllegalArgumentException("Unexpected starting node: " + other)
      }
    }

    println("# Final sources to connect: " + sources)

  }
}

object PartialFlowGraph {
  def apply(block: FlowGraphBuilder => Unit): PartialFlowGraph = {
    val builder = new FlowGraphBuilder
    block(builder)
    builder.partialBuild()
  }

  def apply(partialFlowGraph: PartialFlowGraph)(block: FlowGraphBuilder => Unit): PartialFlowGraph = {
    val builder = new FlowGraphBuilder(partialFlowGraph.graph)
    block(builder)
    builder.partialBuild()
  }
}

/**
 * `PartialFlowGraph` may have sources and sinks that are not attach, and it can therefore not
 * be `run`.
 */
class PartialFlowGraph private[akka] (private[akka] val graph: ImmutableGraph[FlowGraphInternal.Vertex, LDiEdge]) {
  import FlowGraphInternal._

  def flowsWithoutSource: Set[HasNoSource[_]] =
    graph.nodes.collect {
      case n if n.value.isInstanceOf[UndefinedSource] => n.outgoing.head.label.asInstanceOf[HasNoSource[_]]
    }(collection.breakOut)

  def flowsWithoutSink: Set[HasNoSink[_]] =
    graph.nodes.collect {
      case n if n.value.isInstanceOf[UndefinedSink] => n.incoming.head.label.asInstanceOf[HasNoSink[_]]
    }(collection.breakOut)

}

object FlowGraphBuilderImplicits {
  implicit class SourceOps[In](val source: Source[In]) extends AnyVal {
    def ~>[Out](flow: ProcessorFlow[In, Out])(implicit builder: FlowGraphBuilder): SourceNextStep[In, Out] = {
      new SourceNextStep(source, flow, builder)
    }

    def ~=>(flow: HasNoSource[In])(implicit builder: FlowGraphBuilder): Unit =
      builder.attachSource(flow, source)
  }

  class SourceNextStep[In, Out](source: Source[In], flow: ProcessorFlow[In, Out], builder: FlowGraphBuilder) {
    def ~>(sink: FanOperation[Out]): FanOperation[Out] = {
      builder.addEdge(source, flow, sink)
      sink
    }
  }

  implicit class FanOps[In](val fan: FanOperation[In]) extends AnyVal {
    def ~>[Out](flow: ProcessorFlow[In, Out])(implicit builder: FlowGraphBuilder): FanNextStep[In, Out] = {
      new FanNextStep(fan, flow, builder)
    }
  }

  class FanNextStep[In, Out](fan: FanOperation[In], flow: ProcessorFlow[In, Out], builder: FlowGraphBuilder) {
    def ~>(sink: FanOperation[Out]): FanOperation[Out] = {
      builder.addEdge(fan, flow, sink)
      sink
    }

    def ~>(sink: Sink[Out]): Unit = {
      builder.addEdge(fan, flow, sink)
    }

    def ~>(sink: UndefSink[Out]): Unit = {
      builder.addEdge(fan, flow)
    }
  }

  implicit class FlowWithSourceOps[In, Out](val flow: FlowWithSource[In, Out]) extends AnyVal {
    def ~>(sink: FanOperation[Out])(implicit builder: FlowGraphBuilder): FanOperation[Out] = {
      builder.addEdge(flow, sink)
      sink
    }
  }

  // FIXME add more for FlowWithSource and FlowWithSink

  class UndefSource[In]

  def undefinedSource[In](implicit builder: FlowGraphBuilder): UndefSource[In] = new UndefSource[In]

  implicit class UndefinedSourceOps[In](val source: UndefSource[In]) extends AnyVal {
    def ~>[Out](flow: ProcessorFlow[In, Out])(implicit builder: FlowGraphBuilder): UndefinedSourceNextStep[In, Out] = {
      new UndefinedSourceNextStep(flow, builder)
    }
  }

  class UndefinedSourceNextStep[In, Out](flow: ProcessorFlow[In, Out], builder: FlowGraphBuilder) {
    def ~>(sink: FanOperation[Out]): FanOperation[Out] = {
      builder.addEdge(flow, sink)
      sink
    }
  }

  class UndefSink[Out]

  def undefinedSink[Out](implicit builder: FlowGraphBuilder): UndefSink[Out] = new UndefSink[Out]

  implicit class HasNoSinkOps[Out](val flow: HasNoSink[Out]) extends AnyVal {
    def ~=>(sink: Sink[Out])(implicit builder: FlowGraphBuilder): Unit =
      builder.attachSink(flow, sink)
  }
}
