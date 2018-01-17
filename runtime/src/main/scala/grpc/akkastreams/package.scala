package grpc

import io.grpc.stub.StreamObserver

package object akkastreams {

  type GrpcOperator[I, O] = StreamObserver[O] => StreamObserver[I]

}
