package grpc.akkastreams

import java.util.concurrent.atomic.AtomicReference

import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler}
import io.grpc.stub.CallStreamObserver

class GrpcSinkStage[I](observer: CallStreamObserver[I]) extends GraphStage[SinkShape[I]] {
  val in = Inlet[I]("grpc.in")
  override val shape: SinkShape[I] = SinkShape.of(in)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with Runnable {
      val element: AtomicReference[Option[I]] = new AtomicReference[Option[I]](None)

      override def run(): Unit =
        if (observer.isReady) {
          element.getAndSet(None).foreach { value =>
            observer.onNext(value)
            tryPull(in)
          }
        }

      override def onPush(): Unit = {
        val value = grab(in)
        if (observer.isReady) {
          observer.onNext(value)
          pull(in)
        } else element.compareAndSet(None, Some(value))
      }

      override def onUpstreamFinish(): Unit = observer.onCompleted()

      override def onUpstreamFailure(t: Throwable): Unit = observer.onError(t)

      override def preStart(): Unit = pull(in)

      observer.setOnReadyHandler(this)
      setHandler(in, this)
    }
}
