package grpc.akkastreams

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import com.trueaccord.scalapb.grpc.Grpc
import io.grpc.ClientCall
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
          override def cancel(message: String, cause: Throwable): Unit = ()

          override def setOnReadyHandler(onReadyHandler: Runnable): Unit = ()

          override def request(count: Int): Unit = ()

          override def disableAutoInboundFlowControl(): Unit = ()

          override def isReady: Boolean = true

          override def setMessageCompression(enable: Boolean): Unit = ()

          override def onError(t: Throwable): Unit = ()

          override def onCompleted(): Unit = ()

          override def onNext(request: I): Unit =
            ClientCalls.asyncServerStreamingCall(call, request, outputObserver)
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
