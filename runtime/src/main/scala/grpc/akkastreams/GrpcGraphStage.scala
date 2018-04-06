package grpc.akkastreams

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import io.grpc.stub.{ClientCallStreamObserver, ClientResponseObserver}

class GrpcGraphStage[I, O](operator: GrpcOperator[I, O]) extends GraphStage[FlowShape[I, O]] {
  val in = Inlet[I]("grpc.in")
  val out = Outlet[O]("grpc.out")

  override val shape: FlowShape[I, O] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      var requestStream = new AtomicReference[Option[ClientCallStreamObserver[I]]](None)
      val element = new AtomicReference[Option[I]](None)
      val requested = new AtomicBoolean(false)

      val outObs = new ClientResponseObserver[I, O] with Runnable {
        override def beforeStart(reqStream: ClientCallStreamObserver[I]): Unit = {
          requestStream.set(Some(reqStream))
          reqStream.disableAutoInboundFlowControl()
          reqStream.setOnReadyHandler(this)
        }

        override def onError(t: Throwable) =
          getAsyncCallback((t: Throwable) => fail(out, t)).invoke(t)

        override def onCompleted() =
          getAsyncCallback((_: Unit) => complete(out)).invoke(())

        override def onNext(value: O) =
          getAsyncCallback((value: O) => push(out, value)).invoke(value)

        override def run(): Unit = requestStream.get().foreach { reqStream =>
          if (requested.compareAndSet(true, false)) reqStream.request(1)
          if (reqStream.isReady) {
            element.getAndSet(None).foreach { value =>
              reqStream.onNext(value)
              tryPull(in)
            }
          }
        }
      }

      val inObs = operator(outObs)

      override def onPush(): Unit = {
        val value = grab(in)
        requestStream.get() match {
          case Some(reqStream) if reqStream.isReady() =>
            reqStream.onNext(value)
            pull(in)
          case _ => element.compareAndSet(None, Some(value))
        }
      }

      override def onUpstreamFinish(): Unit = inObs.onCompleted()

      override def onUpstreamFailure(t: Throwable): Unit = inObs.onError(t)

      override def onPull(): Unit =
        requestStream.get() match {
          case Some(reqStream) => reqStream.request(1)
          case _ => requested.compareAndSet(false, true)
        }

      override def preStart(): Unit = pull(in)

      setHandler(in, this)
      setHandler(out, this)
    }
}
