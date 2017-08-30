package grpc.akkastreams

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import io.grpc.stub.StreamObserver
import org.reactivestreams.{Publisher, Subscriber, Subscription}

object GrpcAkkaStreams {

  type GrpcOperator[I, O] = StreamObserver[O] => StreamObserver[I]

  class GrpcGraphStage[I, O](operator: GrpcOperator[I, O]) extends GraphStage[FlowShape[I, O]] {
    val in = Inlet[I]("grpc.in")
    val out = Outlet[O]("grpc.out")

    override val shape: FlowShape[I, O] = FlowShape.of(in, out)
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        val outObs = new StreamObserver[O] {
          override def onError(t: Throwable) = fail(out, t)
          override def onCompleted() =
            getAsyncCallback((_: Unit) => complete(out)).invoke(())
          override def onNext(value: O) =
            getAsyncCallback((value: O) => emit(out, value)).invoke(value)
        }
        val inObs = operator(outObs)
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val input = grab(in)
            inObs.onNext(input)
            pull(in)
          }
          override def onUpstreamFinish(): Unit = inObs.onCompleted()
          override def onUpstreamFailure(t: Throwable): Unit = inObs.onError(t)
        })
        setHandler(out, new OutHandler {
          override def onPull(): Unit = ()
        })
        override def preStart(): Unit = pull(in)
      }
  }

  def reactiveSubscriberToGrpcObserver[T](subscriber: Subscriber[_ >: T]): StreamObserver[T] =
    new StreamObserver[T] {
      override def onError(t: Throwable): Unit = subscriber.onError(t)
      override def onCompleted(): Unit = subscriber.onComplete()
      override def onNext(value: T): Unit = subscriber.onNext(value)
    }

  def grpcObserverToReactiveSubscriber[T](observer: StreamObserver[T]): Subscriber[T] =
    new Subscriber[T] {
      override def onError(t: Throwable) = observer.onError(t)
      override def onComplete() = observer.onCompleted()
      override def onNext(value: T) = observer.onNext(value)
      override def onSubscribe(s: Subscription) = ()
    }

}
