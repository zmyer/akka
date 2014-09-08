/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.language.existentials
import scalax.collection.edge.LDiEdge
import scalax.collection.mutable.Graph
import scalax.collection.immutable.{ Graph ⇒ ImmutableGraph }
import org.reactivestreams.Subscriber
import akka.stream.impl.BlackholeSubscriber
import org.reactivestreams.Publisher
import org.reactivestreams.Processor
import scalax.collection.edge.LBase.LEdgeImplicits

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
    this(Graph.from(edges = immutableGraph.edges.map(e ⇒ LDiEdge(e.from.value, e.to.value)(e.label)).toIterable))

  implicit val edgeFactory = scalax.collection.edge.LDiEdge

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
    replaceEdges.foreach { edge ⇒
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
    replaceEdges.foreach { edge ⇒
      require(edge.from.value.isInstanceOf[UndefinedSource], s"Flow already attached to a source [${edge.from.value}]")
      graph.remove(edge.from.value)
      graph.addLEdge(SourceVertex(source), edge.to.value)(flow)
    }
    this
  }

  private def checkFanPrecondition(fan: FanOperation[_], in: Boolean): Unit = {
    fan match {
      case _: FanOutOperation[_] if in ⇒
        graph.find(FanOperationVertex(fan)) match {
          case Some(existing) if existing.incoming.nonEmpty ⇒
            throw new IllegalArgumentException(s"Fan-out [$fan] is already attached to input [${existing.incoming.head}]")
          case _ ⇒ // ok
        }
      case _: FanInOperation[_] if !in ⇒
        graph.find(FanOperationVertex(fan)) match {
          case Some(existing) if existing.outgoing.nonEmpty ⇒
            throw new IllegalArgumentException(s"Fan-in [$fan] is already attached to output [${existing.outgoing.head}]")
          case _ ⇒ // ok
        }
      case _ ⇒ // ok
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
    ImmutableGraph.from(edges = graph.edges.map(e ⇒ LDiEdge(e.from.value, e.to.value)(e.label)).toIterable)

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
      val formatted = undefinedSourcesSinks.map(n ⇒ n.value match {
        case u: UndefinedSource ⇒ s"$u -> ${n.outgoing.head.label} -> ${n.outgoing.head.to}"
        case u: UndefinedSink   ⇒ s"${n.incoming.head.from} -> ${n.incoming.head.label} -> $u"
      })
      throw new IllegalArgumentException("Undefined sources or sinks: " + formatted.mkString(", "))
    }

    // we will be able to relax these checks
    graph.nodes.foreach { node ⇒
      node.value match {
        case FanOperationVertex(merge: Merge[_]) ⇒
          require(node.incoming.size == 2, "Merge must have two incoming edges: " + node.incoming)
          require(node.outgoing.size == 1, "Merge must have one outgoing edge: " + node.outgoing)
        case FanOperationVertex(bcast: Broadcast[_]) ⇒
          require(node.incoming.size == 1, "Broadcast must have one incoming edge: " + node.incoming)
          require(node.outgoing.size == 2, "Broadcast must have two outgoing edges: " + node.outgoing)
        case _ ⇒ // no check for other node types
      }
    }

    require(graph.isConnected, "Graph must be connected")
  }

}

object FlowGraph {
  def apply(block: FlowGraphBuilder ⇒ Unit): FlowGraph =
    apply(ImmutableGraph.empty[FlowGraphInternal.Vertex, LDiEdge])(block)

  def apply(partialFlowGraph: PartialFlowGraph)(block: FlowGraphBuilder ⇒ Unit): FlowGraph =
    apply(partialFlowGraph.graph)(block)

  def apply(flowGraph: FlowGraph)(block: FlowGraphBuilder ⇒ Unit): FlowGraph =
    apply(flowGraph.graph)(block)

  private def apply(graph: ImmutableGraph[FlowGraphInternal.Vertex, LDiEdge])(block: FlowGraphBuilder ⇒ Unit): FlowGraph = {
    val builder = new FlowGraphBuilder(graph)
    block(builder)
    builder.build()
  }
}

class FlowGraph private[akka] (private[akka] val graph: ImmutableGraph[FlowGraphInternal.Vertex, LDiEdge]) {
  import FlowGraphInternal._
  def run(implicit materializer: FlowMaterializer): MaterializedFlowGraph = {
    println("# RUN ----------------")

    def dummyProcessor(name: String): Processor[Any, Any] = new BlackholeSubscriber[Any](1) with Publisher[Any] with Processor[Any, Any] {
      def subscribe(subscriber: Subscriber[Any]): Unit = subscriber.onComplete()
      override def toString = name
    }

    // start with sinks
    val startingNodes = graph.nodes.filter(_.diSuccessors.isEmpty)

    println("Starting nodes: " + startingNodes)

    import scalax.collection.GraphTraversal._

    case class Memo(visited: Set[graph.EdgeT] = Set.empty,
                    nodeProcessor: Map[graph.NodeT, Processor[Any, Any]] = Map.empty,
                    sources: Map[Source[_], FlowWithSink[Any, Any]] = Map.empty,
                    materializedSinks: Map[Sink[_], Any] = Map.empty)

    val result = startingNodes.foldLeft(Memo()) {
      case (memo, start) ⇒

        println("# starting at sink: " + start + " flow: " + start.incoming.head.label)

        val traverser = graph.innerEdgeTraverser(start, parameters = Parameters(direction = Predecessors, kind = BreadthFirst),
          ordering = graph.defaultEdgeOrdering)
        traverser.foldLeft(memo) {
          case (memo, edge) ⇒

            if (memo.visited(edge)) {
              println("#  already visited: " + edge)
            } else {
              println("#  visit: " + edge)
            }

            if (memo.visited(edge)) {
              memo
            } else {
              val flow = edge.label.asInstanceOf[ProcessorFlow[Any, Any]]

              // returns the materialized sink, if any
              def connectProcessorToDownstream(processor: Processor[Any, Any]): Option[(SinkWithKey[_, _], Any)] = {
                val f = flow.withSource(PublisherSource(processor))
                edge.to.value match {
                  case SinkVertex(sink: SinkWithKey[_, _]) ⇒
                    val mf = f.withSink(sink.asInstanceOf[Sink[Any]]).run()
                    Some(sink -> mf.getSinkFor(sink))
                  case SinkVertex(sink) ⇒
                    f.withSink(sink.asInstanceOf[Sink[Any]]).run()
                    None
                  case _ ⇒
                    f.withSink(SubscriberSink(memo.nodeProcessor(edge.to))).run()
                    None
                }
              }

              edge.from.value match {
                case SourceVertex(src) ⇒
                  println("#  source: " + src)
                  val f = flow.withSink(SubscriberSink(memo.nodeProcessor(edge.to)))
                  // connect the source with the flow later
                  memo.copy(visited = memo.visited + edge,
                    sources = memo.sources.updated(src, f))

                case FanOperationVertex(fanOp) ⇒
                  val processor = fanOp match {
                    case merge: Merge[_] ⇒
                      // FIXME materialize Merge
                      dummyProcessor("merge-processor")
                    case bcast: Broadcast[_] ⇒
                      memo.nodeProcessor.getOrElse(edge.from, {
                        // FIXME materialize Broadcast
                        dummyProcessor("bcast-processor")
                      })
                    case other ⇒
                      throw new IllegalArgumentException("Unknown fan operation: " + other)
                  }
                  val materializedSink = connectProcessorToDownstream(processor)
                  memo.copy(
                    visited = memo.visited + edge,
                    nodeProcessor = memo.nodeProcessor.updated(edge.from, processor),
                    materializedSinks = memo.materializedSinks ++ materializedSink)
              }
            }

        }

    }

    // connect all input sources as the last thing
    val materializedSources = result.sources.foldLeft(Map.empty[Source[_], Any]) {
      case (acc, (src, flow)) ⇒
        println(s"# connecting input src $src to flow $flow")
        val mf = flow.withSource(src).run()
        src match {
          case srcKey: SourceWithKey[_, _] ⇒ acc.updated(src, mf.getSourceFor(srcKey))
          case _                           ⇒ acc
        }
    }

    new MaterializedFlowGraph(materializedSources, result.materializedSinks)
  }

}

object PartialFlowGraph {
  def apply(block: FlowGraphBuilder ⇒ Unit): PartialFlowGraph =
    apply(ImmutableGraph.empty[FlowGraphInternal.Vertex, LDiEdge])(block)

  def apply(partialFlowGraph: PartialFlowGraph)(block: FlowGraphBuilder ⇒ Unit): PartialFlowGraph =
    apply(partialFlowGraph.graph)(block)

  def apply(flowGraph: FlowGraph)(block: FlowGraphBuilder ⇒ Unit): PartialFlowGraph =
    apply(flowGraph.graph)(block)

  private def apply(graph: ImmutableGraph[FlowGraphInternal.Vertex, LDiEdge])(block: FlowGraphBuilder ⇒ Unit): PartialFlowGraph = {
    val builder = new FlowGraphBuilder(graph)
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
      case n if n.value.isInstanceOf[UndefinedSource] ⇒ n.outgoing.head.label.asInstanceOf[HasNoSource[_]]
    }(collection.breakOut)

  def flowsWithoutSink: Set[HasNoSink[_]] =
    graph.nodes.collect {
      case n if n.value.isInstanceOf[UndefinedSink] ⇒ n.incoming.head.label.asInstanceOf[HasNoSink[_]]
    }(collection.breakOut)

}

class MaterializedFlowGraph(materializedSources: Map[Source[_], Any], materializedSinks: Map[Sink[_], Any])
  extends MaterializedSource with MaterializedSink {

  override def getSourceFor[T](key: SourceWithKey[_, T]): T =
    materializedSources.get(key) match {
      case Some(matSource) ⇒ matSource.asInstanceOf[T]
      case None ⇒
        throw new IllegalArgumentException(s"Source key [$key] doesn't exist in this flow graph")
    }

  def getSinkFor[T](key: SinkWithKey[_, T]): T =
    materializedSinks.get(key) match {
      case Some(matSink) ⇒ matSink.asInstanceOf[T]
      case None ⇒
        throw new IllegalArgumentException(s"Sink key [$key] doesn't exist in this flow graph")
    }
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
