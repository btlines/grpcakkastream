package grpc.akkastreams

import akka.stream._
import akka.stream.stage._
import io.grpc.stub.StreamObserver
import org.reactivestreams.{ Subscriber, Subscription }

import scala.concurrent.{Future, Promise}

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

  class GrpcSourceStage[O] extends GraphStageWithMaterializedValue[SourceShape[O], Future[StreamObserver[O]]] {
    val out = Outlet[O]("grpc.out")
    override val shape: SourceShape[O] = SourceShape.of(out)
    override def createLogicAndMaterializedValue(
      inheritedAttributes: Attributes
    ): (GraphStageLogic, Future[StreamObserver[O]]) = {
      val promise: Promise[StreamObserver[O]] = Promise()
      val logic = new GraphStageLogic(shape) {
        val observer = new StreamObserver[O] {
          override def onError(t: Throwable) = fail(out, t)
          override def onCompleted() = getAsyncCallback((_: Unit) => complete(out)).invoke(())
          override def onNext(value: O) = getAsyncCallback((value: O) => emit(out, value)).invoke(value)
        }
        setHandler(out, new OutHandler {
          override def onPull(): Unit = ()
        })
        override def preStart(): Unit = promise.success(observer)
      }
      (logic, promise.future)
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
      var subscription: Subscription = _
      override def onError(t: Throwable) = observer.onError(t)
      override def onComplete() = observer.onCompleted()
      override def onNext(value: T) = {
        observer.onNext(value)
        pull()
      }
      override def onSubscribe(s: Subscription) = {
        subscription = s
        pull()
      }
      private def pull(): Unit = subscription.request(1)
    }

}
