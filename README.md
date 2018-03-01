[![Build status](https://api.travis-ci.org/btlines/grpcakkastream.svg?branch=master)](https://travis-ci.org/btlines/grpcakkastream)
[![Dependencies](https://app.updateimpact.com/badge/852442212779298816/grpcakkastream.svg?config=compile)](https://app.updateimpact.com/latest/852442212779298816/grpcakkastream)
[![License](https://img.shields.io/:license-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![GRPCAkkaStreamGenerator](https://api.bintray.com/packages/beyondthelines/maven/grpcakkastreamgenerator/images/download.svg) ](https://bintray.com/beyondthelines/maven/grpcakkastreamgenerator/_latestVersion)
[![GRPCAkkaStreamRuntime](https://api.bintray.com/packages/beyondthelines/maven/grpcakkastreamruntime/images/download.svg) ](https://bintray.com/beyondthelines/maven/grpcakkastreamruntime/_latestVersion)

# GRPC Akka stream

Use Akka-stream's building blocks (Flow) to implement GRPC services instead of Java's StreamObservers.

All service method can be implemented as a Flow[In, Out, NotUsed].

Note that this library only exposes an AkkaStream interface but still relies on the default Netty implementation provided by grpc-java.

## Installation

You need to enable [`sbt-protoc`](https://github.com/thesamet/sbt-protoc) plugin to generate source code for the proto definitions.
You can do it by adding a `protoc.sbt` file into your `project` folder with the following lines:

```scala
import scalapb.compiler.Version.{grpcJavaVersion, scalapbVersion}
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.17")

resolvers += Resolver.bintrayRepo("beyondthelines", "maven")

libraryDependencies ++= Seq(
  "com.thesamet.scalapb"   %% "compilerplugin"          % scalapbVersion,
  "io.grpc"                %  "grpc-netty"              % grpcJavaVersion,
  "beyondthelines"         %% "grpcakkastreamgenerator" % "0.0.8"
)
```

Here we add a dependency to the GRPC Akkastream protobuf generator.

Then we need to trigger the generation from the `build.sbt`:

```scala
PB.targets in Compile := Seq(
  // compile your proto files into scala source files
  scalapb.gen() -> (sourceManaged in Compile).value,
  // generate the GRPC Akka stream source code
  grpc.akkastreams.generators.GrpcAkkaStreamGenerator() -> (sourceManaged in Compile).value
)

resolvers += Resolver.bintrayRepo("beyondthelines", "maven")

libraryDependencies += "beyondthelines" %% "grpcakkastreamruntime" % "0.0.8"
```

### Usage

You're now ready to implement your GRPC service using Akka-streams Flow.

To implement your service's business logic you simply extend the GRPCAkkaStream generated trait.

E.g. for the RouteGuide service:

```scala
class RouteGuideAkkaStreamService(features: Seq[Feature]) extends RouteGuideGrpcAkkaStream.RouteGuide {
  // Unary call
  override def getFeature: Flow[Point, Feature, NotUsed] = ???
  // Server streaming
  override def listFeatures: Flow[Rectangle, Feature, NotUsed] = ???
  // Client streaming
  override def recordRoute: Flow[Point, RouteSummary, NotUsed] = ???
  // Bidi streaming
  override def routeChat: Observable[RouteNote, RouteNote, NotUsed] = ???
}
```

The server creation is similar except you need to provide an Akka-streams' `Materializer` instead of an `ExecutionContext` when binding the service

```scala
val system = ActorSystem("GRPC")
implicit val materializer = ActorMaterializer.create(system)
val server = ServerBuilder
  .forPort(8980)
  .addService(
    RouteGuideGrpcAkkaStream.bindService(
      new RouteGuideAkkaStreamService(features) // the service implemented above
    )
  )
  .build()
```

Akka-streams' Flows are also available on the client side:

```scala
val channel = ManagedChannelBuilder
  .forAddress("localhost", 8980)
  .usePlainText(true)
  .build()

val stub = RouteGuideGrpcAkkaStream.stub(channel) // only an async stub is provided

// Unary call
Source
  .single(Point(408031728, -748645385))
  .via(stub.getFeature)
  .runForeach(println)
// Server streaming
val rectangle = Rectangle(
  lo = Some(Point(408031728, -748645385)),
  hi = Some(Point(413700272, -742135189))
)
Source
  .single(rectangle)
  .via(stub.listFeatures)
  .runForeach(println)
// Client streaming
Source(features.map(_.getLocation).to[collection.immutable.Iterable])
  .throttle(1, 100.millis, 1, ThrottleMode.Shaping)
  .via(stub.recordRoute)
  .runForeach(println)
// Bidi streaming
Source(
  RouteNote(message = "First message", location = Some(Point(0, 0))),
  RouteNote(message = "Second message", location = Some(Point(0, 1))),
  RouteNote(message = "Third message", location = Some(Point(1, 0))),
  RouteNote(message = "Fourth message", location = Some(Point(1, 1)))
)
  .throttle(1, 1.second, 1, ThrottleMode.Shaping)
  .via(stub.routeChat)
  .runForeach(println)
```
