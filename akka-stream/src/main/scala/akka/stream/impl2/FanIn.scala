/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import akka.actor.{ ActorRef, ActorLogging, Actor }
import akka.stream.MaterializerSettings
import akka.stream.actor.{ ActorSubscriberMessage, ActorSubscriber }
import akka.stream.impl._
import org.reactivestreams.{ Subscription, Subscriber }

/**
 * INTERNAL API
 */
private[akka] object FanIn {

  case class OnError(id: Int, cause: Throwable)
  case class OnComplete(id: Int)
  case class OnNext(id: Int, e: Any)
  case class OnSubscribe(id: Int, subscription: Subscription)

  private[akka] final case class SubInput[T](impl: ActorRef, id: Int) extends Subscriber[T] {
    override def onError(cause: Throwable): Unit = impl ! OnError(id, cause)
    override def onComplete(): Unit = impl ! OnComplete(id)
    override def onNext(element: T): Unit = impl ! OnNext(id, element)
    override def onSubscribe(subscription: Subscription): Unit = impl ! OnSubscribe(id, subscription)
  }

  abstract class InputBunch(inputCount: Int, bufferSize: Int, pump: Pump) {
    private var cancelled = false

    private val inputs = Array.fill(inputCount)(new BatchingInputBuffer(bufferSize, pump) {
      override protected def onError(e: Throwable): Unit = InputBunch.this.onError(e)
    })

    private val marked = Array.ofDim[Boolean](inputCount)
    private var markCount = 0
    private val pending = Array.ofDim[Boolean](inputCount)
    private var markedPending = 0
    private val completed = Array.ofDim[Boolean](inputCount)
    private var markedCompleted = 0

    private var preferredId = 0

    def cancel(): Unit = if (!cancelled) {
      cancelled = true
      inputs foreach (_.cancel())
    }

    def onError(e: Throwable): Unit

    def markInput(input: Int): Unit = {
      if (!marked(input)) {
        if (completed(input)) markedCompleted += 1
        if (pending(input)) markedPending += 1
        marked(input) = true
        markCount += 1
      }
    }

    def unmarkInput(input: Int): Unit = {
      if (marked(input)) {
        if (completed(input)) markedCompleted -= 1
        if (pending(input)) markedPending -= 1
        marked(input) = false
        markCount -= 1
      }
    }

    private def idToDequeue(): Int = {
      var id = preferredId
      // FIXME: This should not end up as an infinite loop if someone dequeues without actually having something to
      // dequeue!
      while (!(marked(id) && pending(id))) {
        id += 1
        if (id == inputCount) id = 0
      }
      id
    }

    def dequeueAndYield(): Any = {
      val id = idToDequeue()
      val input = inputs(id)
      preferredId = (id + 1) % inputCount
      val elem = input.dequeueInputElement()
      if (!input.inputsAvailable) {
        markedPending -= 1
        pending(id) = false
      }
      elem
    }
    def dequeueAndPrefer(preferred: Int): Any = {
      val id = idToDequeue()
      val input = inputs(id)
      preferredId = preferred
      val elem = input.dequeueInputElement()
      if (!input.inputsAvailable) {
        markedPending -= 1
        pending(id) = false
      }
      elem
    }

    val AllOfMarkedInputs = new TransferState {
      override def isCompleted: Boolean = markedCompleted == markCount && markedPending < markCount
      override def isReady: Boolean = markedPending == markCount
    }

    val AnyOfMarkedInputs = new TransferState {
      override def isCompleted: Boolean = markedCompleted == markCount && markedPending == 0
      override def isReady: Boolean = markedPending > 0
    }

    // FIXME: Eliminate re-wraps
    def subreceive: SubReceive = new SubReceive({
      case OnSubscribe(id, subscription) ⇒
        inputs(id).subreceive(ActorSubscriber.OnSubscribe(subscription))
      case OnNext(id, elem) ⇒
        if (marked(id) && !pending(id)) markedPending += 1
        pending(id) = true
        inputs(id).subreceive(ActorSubscriberMessage.OnNext(elem))
      case OnComplete(id) ⇒
        if (marked(id) && !completed(id)) markedCompleted += 1
        completed(id) = true
        inputs(id).subreceive(ActorSubscriberMessage.OnComplete)
      case OnError(id, e) ⇒ onError(e)
    })

  }

}

abstract class FanIn(val settings: MaterializerSettings, val inputPorts: Int) extends Actor with ActorLogging with Pump {
  import FanIn._

  protected val primaryOutputs: Outputs = new SimpleOutputs(self, this)
  protected val inputBunch = new InputBunch(inputPorts, settings.maxInputBufferSize, this) {
    override def onError(e: Throwable): Unit = fail(e)
  }

  override def pumpFinished(): Unit = {
    inputBunch.cancel()
    primaryOutputs.complete()
    context.stop(self)
  }

  override def pumpFailed(e: Throwable): Unit = fail(e)

  protected def fail(e: Throwable): Unit = {
    log.error(e, "failure during processing") // FIXME: escalate to supervisor instead
    inputBunch.cancel()
    primaryOutputs.cancel(e)
    context.stop(self)
  }

  override def postStop(): Unit = {
    inputBunch.cancel()
    primaryOutputs.cancel(new IllegalStateException("Processor actor terminated abruptly"))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    throw new IllegalStateException("This actor cannot be restarted")
  }

  def receive = inputBunch.subreceive orElse primaryOutputs.subreceive

}

class FairMerge(_settings: MaterializerSettings, _inputPorts: Int) extends FanIn(_settings, _inputPorts) {
  (0 until inputPorts) foreach inputBunch.markInput

  nextPhase(TransferPhase(inputBunch.AnyOfMarkedInputs && primaryOutputs.NeedsDemand) { () ⇒
    val elem = inputBunch.dequeueAndYield()
    primaryOutputs.enqueueOutputElement(elem)
  })

}

class UnfairMerge(_settings: MaterializerSettings, _inputPorts: Int, val preferred: Int) extends FanIn(_settings, _inputPorts) {
  (0 until inputPorts) foreach inputBunch.markInput

  nextPhase(TransferPhase(inputBunch.AnyOfMarkedInputs && primaryOutputs.NeedsDemand) { () ⇒
    val elem = inputBunch.dequeueAndPrefer(preferred)
    primaryOutputs.enqueueOutputElement(elem)
  })
}
