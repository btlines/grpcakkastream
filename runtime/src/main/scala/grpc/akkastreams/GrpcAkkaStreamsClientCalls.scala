package grpc.akkastreams

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import com.trueaccord.scalapb.grpc.Grpc
import io.grpc.{ClientCall, Metadata, Status}
import io.grpc.stub._

object GrpcAkkaStreamsClientCalls {

  def unaryFlow[I, O](call: ClientCall[I, O]): Flow[I, O, NotUsed] =
    Flow[I].flatMapConcat(request =>
      Source.fromFuture(
        Grpc.guavaFuture2ScalaFuture(
          ClientCalls.futureUnaryCall(call, request)
        )
      )
    )

  def serverStreamingFlow[I, O](call: ClientCall[I, O]): Flow[I, O, NotUsed] =
    Flow.fromGraph(
      new GrpcGraphStage[I, O](outputObserver => {
        val out = outputObserver.asInstanceOf[ClientResponseObserver[I, O]]
        val in = new ClientCallStreamObserver[I] {
          val halfClosed = new AtomicBoolean(false)
          val onReadyHandler = new AtomicReference[Option[Runnable]](None)
          val listener = new ClientCall.Listener[O] {
            override def onClose(status: Status, trailers: Metadata): Unit =
              status.getCode match {
                case Status.Code.OK => out.onCompleted()
                case _ => out.onError(status.asException(trailers))
              }
            override def onMessage(message: O): Unit =
              out.onNext(message)
            override def onReady(): Unit =
              onReadyHandler.get().foreach(_.run())
          }
          call.start(listener, new Metadata())

          override def cancel(message: String, cause: Throwable): Unit =
            call.cancel(message, cause)
          override def setOnReadyHandler(onReadyHandler: Runnable): Unit =
            this.onReadyHandler.set(Some(onReadyHandler))
          override def request(count: Int): Unit = call.request(count)
          override def disableAutoInboundFlowControl(): Unit = ()
          override def isReady: Boolean = !halfClosed.get() || call.isReady
          override def setMessageCompression(enable: Boolean): Unit =
            call.setMessageCompression(enable)
          override def onError(t: Throwable): Unit =
            call.cancel("Cancelled by client with StreamObserver.onError()", t)
          override def onCompleted(): Unit = ()
          override def onNext(request: I): Unit = {
            call.sendMessage(request)
            halfClosed.set(true)
            call.halfClose()
          }
        }
        out.beforeStart(in)
        in
      })
    )

  def clientStreamingFlow[I, O](call: ClientCall[I, O]): Flow[I, O, NotUsed] =
    Flow.fromGraph(new GrpcGraphStage[I, O](ClientCalls.asyncClientStreamingCall(call, _)))

  def bidiStreamingFlow[I, O](call: ClientCall[I, O]): Flow[I, O, NotUsed] =
    Flow.fromGraph(new GrpcGraphStage[I, O](ClientCalls.asyncBidiStreamingCall(call, _)))
}
