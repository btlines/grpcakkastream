package grpc.akkastreams

import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import io.grpc.ServerCallHandler
import io.grpc.stub.{CallStreamObserver, ServerCalls, StreamObserver}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

object GrpcAkkaStreamsServerCalls {

  def unaryCall[I, O](service: Flow[I, O, _])(
    implicit mat: Materializer
  ): ServerCallHandler[I, O] = ServerCalls.asyncUnaryCall(
    new ServerCalls.UnaryMethod[I, O] {
      override def invoke(request: I, responseObserver: StreamObserver[O]) =
        Source
          .single(request)
          .via(service)
          .runForeach(responseObserver.onNext)
          .onComplete {
            case Success(_) => responseObserver.onCompleted()
            case Failure(t) => responseObserver.onError(t)
          }(mat.executionContext)
    }
  )

  def serverStreamingCall[I, O](service: Flow[I, O, _])(
    implicit mat: Materializer
  ): ServerCallHandler[I, O] =
    ServerCalls.asyncServerStreamingCall(
      new ServerCalls.ServerStreamingMethod[I, O] {
        override def invoke(request: I, responseObserver: StreamObserver[O]) =
          Source
            .single(request)
            .via(service)
            .runWith(Sink.fromGraph(new GrpcSinkStage[O](
              responseObserver.asInstanceOf[CallStreamObserver[O]]
            )))
      }
    )

  def clientStreamingCall[I, O](service: Flow[I, O, _])(
    implicit mat: Materializer
  ): ServerCallHandler[I, O] = ServerCalls.asyncClientStreamingCall(
    new ServerCalls.ClientStreamingMethod[I, O] {
      override def invoke(responseObserver: StreamObserver[O]): StreamObserver[I] =
      // blocks until the GraphStage is fully initialized
        Await.result(
          Source
            .fromGraph(new GrpcSourceStage[I, O](
              responseObserver.asInstanceOf[CallStreamObserver[O]]
            ))
            .via(service)
            .to(Sink.fromGraph(new GrpcSinkStage[O](
              responseObserver.asInstanceOf[CallStreamObserver[O]]
            ))).run(),
          Duration.Inf
        )
    }
  )

  def bidiStreamingCall[I, O](service: Flow[I, O, _])(
    implicit mat: Materializer
  ): ServerCallHandler[I, O] = ServerCalls.asyncBidiStreamingCall(
    new ServerCalls.BidiStreamingMethod[I, O] {
      override def invoke(responseObserver: StreamObserver[O]): StreamObserver[I] =
      // blocks until the GraphStage is fully initialized
        Await.result(
          Source
            .fromGraph(new GrpcSourceStage[I, O](
              responseObserver.asInstanceOf[CallStreamObserver[O]]
            ))
            .via(service)
            .to(Sink.fromGraph(new GrpcSinkStage[O](
              responseObserver.asInstanceOf[CallStreamObserver[O]]
            ))).run(),
          Duration.Inf
        )
    }
  )
}
