package grpc.akkastreams

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import io.grpc.stub.StreamObserver
import org.reactivestreams.{Subscriber, Subscription}

object GrpcAkkaStreams {

  type GrpcOperator[I, O] = StreamObserver[O] => StreamObserver[I]

  class GrpcGraphStage[I, O](operator: GrpcOperator[I, O]) extends GraphStage[FlowShape[I, O]] {
    val in = Inlet[I]("grpc.in")
    val out = Outlet[O]("grpc.out")
    var awaitingValue = false
    val queue = scala.collection.mutable.Queue[O]()
    override val shape: FlowShape[I, O] = FlowShape.of(in, out)
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        val outObs = new StreamObserver[O] {
          override def onError(t: Throwable) = fail(out, t)
          override def onCompleted() = {
            println(s"Observer completed with ${queue.size} elements to publish")
            if (queue.nonEmpty)
              getAsyncCallback((values: collection.immutable.Iterable[O]) =>
                emitMultiple(out, values)
              ).invoke(queue.to[collection.immutable.Iterable])
            complete(out)
          }
          override def onNext(value: O) = {
            println(s"Received value $value")
            getAsyncCallback((value: O) =>
              if (awaitingValue) {
                awaitingValue = false
                println(s"Publishing value $value")
                push(out, value)
              } else {
                println(s"Buffering value $value")
                queue.enqueue(value)
              }
            ).invoke(value)
          }
        }
        val inObs = operator(outObs)
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val input = grab(in)
            println(s"Processing input $input")
            inObs.onNext(input)
            pull(in)
          }
          override def onUpstreamFinish(): Unit = inObs.onCompleted()
          override def onUpstreamFailure(t: Throwable): Unit = inObs.onError(t)
        })
        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            println(s"Pulling output when ${queue.size} elements available")
            if (queue.nonEmpty) {
              val output = queue.dequeue()
              println(s"publishing output $output")
              push(out, output)
            } else {
              getAsyncCallback((flag: Boolean) => awaitingValue = flag).invoke(true)
            }
          }
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

}
