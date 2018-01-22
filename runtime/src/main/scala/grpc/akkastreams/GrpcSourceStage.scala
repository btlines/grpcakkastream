package grpc.akkastreams

import akka.stream.{Attributes, Outlet, SourceShape}
import akka.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, OutHandler}
import io.grpc.stub.{CallStreamObserver, StreamObserver}

import scala.concurrent.{Future, Promise}

class GrpcSourceStage[I, O](requestStream: CallStreamObserver[O])
  extends GraphStageWithMaterializedValue[SourceShape[I], Future[StreamObserver[I]]] {
  val out = Outlet[I]("grpc.out")
  override val shape: SourceShape[I] = SourceShape.of(out)

  override def createLogicAndMaterializedValue(
    inheritedAttributes: Attributes
  ): (GraphStageLogic, Future[StreamObserver[I]]) = {
    val promise: Promise[StreamObserver[I]] = Promise()

    val logic = new GraphStageLogic(shape) with OutHandler {
      val inObs = new StreamObserver[I] {
        override def onError(t: Throwable) =
          getAsyncCallback((t: Throwable) => fail(out, t)).invoke(t)

        override def onCompleted() =
          getAsyncCallback((_: Unit) => complete(out)).invoke(())

        override def onNext(value: I) =
          getAsyncCallback((value: I) => push(out, value)).invoke(value)
      }

      override def onPull(): Unit = requestStream.request(1)

      override def preStart(): Unit = {
        requestStream.disableAutoInboundFlowControl()
        promise.success(inObs)
      }

      setHandler(out, this)
    }

    (logic, promise.future)
  }
}
