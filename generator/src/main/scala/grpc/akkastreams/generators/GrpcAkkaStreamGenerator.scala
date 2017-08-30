package grpc.akkastreams.generators

import com.google.protobuf.Descriptors._
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos.{CodeGeneratorRequest, CodeGeneratorResponse}
import com.trueaccord.scalapb.Scalapb
import com.trueaccord.scalapb.compiler.FunctionalPrinter.PrinterEndo
import com.trueaccord.scalapb.compiler.StreamType.{Bidirectional, ClientStreaming, ServerStreaming, Unary}
import com.trueaccord.scalapb.compiler.{DescriptorPimps, FunctionalPrinter, StreamType}

import scala.collection.JavaConverters._

object GrpcAkkaStreamGenerator extends protocbridge.ProtocCodeGenerator with DescriptorPimps {
  // Read scalapb.options (if present) in .proto files
  override def registerExtensions(registry: ExtensionRegistry): Unit = {
    Scalapb.registerAllExtensions(registry)
  }

  val params = com.trueaccord.scalapb.compiler.GeneratorParams()

  def run(request: CodeGeneratorRequest): CodeGeneratorResponse = {
    val b = CodeGeneratorResponse.newBuilder

    val fileDescByName: Map[String, FileDescriptor] =
      request.getProtoFileList.asScala.foldLeft[Map[String, FileDescriptor]](Map.empty) {
        case (acc, fp) =>
          val deps = fp.getDependencyList.asScala.map(acc)
          acc + (fp.getName -> FileDescriptor.buildFrom(fp, deps.toArray))
      }

    request.getFileToGenerateList.asScala.foreach {
      name =>
        val fileDesc = fileDescByName(name)
        val responseFile = generateFile(fileDesc)
        b.addFile(responseFile)
    }
    b.build
  }

  def generateFile(fileDesc: FileDescriptor): CodeGeneratorResponse.File = {
    val b = CodeGeneratorResponse.File.newBuilder()

    val objectName = fileDesc
      .fileDescriptorObjectName
      .substring(0, fileDesc.fileDescriptorObjectName.length - 5) + "GrpcAkkaStream"

    b.setName(s"${fileDesc.scalaDirectory}/$objectName.scala")
    val fp = FunctionalPrinter()
      .add(s"package ${fileDesc.scalaPackageName}")
      .newline
      .add(
        "import _root_.akka.NotUsed",
        "import _root_.akka.stream.Materializer",
        "import _root_.akka.stream.scaladsl.{ Flow, Sink, Source }",
        "import _root_.com.google.protobuf.Descriptors.ServiceDescriptor",
        "import _root_.com.trueaccord.scalapb.grpc.{ AbstractService, Grpc, Marshaller, ServiceCompanion }",
        "import _root_.grpc.akkastreams.GrpcAkkaStreams._",
        "import _root_.io.grpc.{ CallOptions, Channel, MethodDescriptor, ServerServiceDefinition }",
        "import _root_.io.grpc.stub.{ AbstractStub, ClientCalls, ServerCalls, StreamObserver }",
        "import _root_.org.reactivestreams.{ Publisher, Subscriber }",
        "import _root_.scala.util.{ Failure, Success }"
      )
      .newline
      .add(s"object $objectName {")
      .indent
      .print(fileDesc.getServices.asScala) {
        case (printer, service) => printer
          .print(service.getMethods.asScala) {
            case (p, m) => p.call(serviceMethodDescriptor(m))
          }
          .newline
          .call(serviceTrait(service))
          .newline
          .call(serviceTraitCompanion(service, fileDesc))
          .newline
          .call(stub(service))
          .newline
          .call(bindService(service))
          .newline
          .add(s"def stub(channel: Channel): ${service.stub} = new ${service.stub}(channel)")
          .newline
          .call(javaDescriptor(service))
      }
      .outdent
      .add("}")
      .newline

    b.setContent(fp.result)
    b.build
  }

  private def serviceMethodDescriptor(method: MethodDescriptor): PrinterEndo = { printer =>
    val methodType = method.streamType match {
      case StreamType.Unary => "UNARY"
      case StreamType.ClientStreaming => "CLIENT_STREAMING"
      case StreamType.ServerStreaming => "SERVER_STREAMING"
      case StreamType.Bidirectional => "BIDI_STREAMING"
    }
    printer
      .add(s"val ${method.descriptorName}: MethodDescriptor[${method.scalaIn}, ${method.scalaOut}] =")
      .indent
      .add("MethodDescriptor.create(")
      .indent
      .add(s"MethodDescriptor.MethodType.$methodType,")
      .add(s"""MethodDescriptor.generateFullMethodName("${method.getService.getFullName}", "${method.getName}"),""")
      .add(s"new Marshaller(${method.scalaIn}),")
      .add(s"new Marshaller(${method.scalaOut})")
      .outdent
      .add(")")
      .outdent
  }

  private def serviceTrait(service: ServiceDescriptor): PrinterEndo = _
    .add(s"trait ${service.getName} extends AbstractService {")
    .indent
    .add(s"override def serviceCompanion = ${service.getName}")
    .seq(service.methods.map(serviceMethodSignature))
    .outdent
    .add("}")

  private def serviceMethodSignature(method: MethodDescriptor): String =
    s"def ${method.name}: Flow[${method.scalaIn}, ${method.scalaOut}, NotUsed]"

  private def serviceTraitCompanion(service: ServiceDescriptor, fileDesc: FileDescriptor): PrinterEndo = _
      .add(s"object ${service.getName} extends ServiceCompanion[${service.getName}] {")
      .indent
      .add(s"implicit def serviceCompanion: ServiceCompanion[${service.getName}] = this")
      .add("def javaDescriptor: ServiceDescriptor =")
      .addIndented(s"${fileDesc.fileDescriptorObjectFullName}.javaDescriptor.getServices().get(0)")
      .outdent
      .add("}")

  private def stub(service: ServiceDescriptor): PrinterEndo = _
    .add(s"class ${service.stub}(")
    .addIndented(
      s"channel: Channel,",
      s"options: CallOptions = CallOptions.DEFAULT"
    )
    .add(s") extends AbstractStub[${service.stub}](channel, options) with ${service.name} {")
    .indent
    .print(service.getMethods.asScala) {
      case (p, m) => p.call(clientMethodImpl(m))
    }
    .add(s"override def build(channel: Channel, options: CallOptions): ${service.stub} =")
    .addIndented(s"new ${service.stub}(channel, options)")
    .outdent
    .add("}")

  private def clientMethodImpl(method: MethodDescriptor): PrinterEndo = { printer =>
    method.streamType match {
      case Unary => printer
        .add(s"override ${serviceMethodSignature(method)} =")
        .indent
        .add(s"Flow[${method.scalaIn}].flatMapConcat(request =>")
        .indent
        .add("Source.fromFuture(")
        .indent
        .add("Grpc.guavaFuture2ScalaFuture(")
        .addIndented(s"ClientCalls.futureUnaryCall(channel.newCall(${method.descriptorName}, options), request)")
        .add(")")
        .outdent
        .add(")")
        .outdent
        .add(")")
        .outdent
      case ServerStreaming => printer
        .add(s"override ${serviceMethodSignature(method)} =")
        .indent
        .add(s"Flow[${method.scalaIn}].flatMapConcat(request =>")
        .indent
        .add("Source.fromPublisher(")
        .indent
        .add(s"new Publisher[${method.scalaOut}] {")
        .indent
        .add(s"override def subscribe(subscriber: Subscriber[_ >: ${method.scalaOut}]): Unit =")
        .indent
        .add("ClientCalls.asyncServerStreamingCall(")
        .addIndented(
          s"channel.newCall(${method.descriptorName}, options),",
          "request,",
          s"reactiveSubscriberToGrpcObserver[${method.scalaOut}](subscriber)"
        )
        .add(")")
        .outdent
        .outdent
        .add("}")
        .outdent
        .add(")")
        .outdent
        .add(")")
      case ClientStreaming => printer
        .add(s"override ${serviceMethodSignature(method)} =")
        .indent
        .add(s"Flow.fromGraph(new GrpcGraphStage[${method.scalaIn}, ${method.scalaOut}](outputObserver =>")
        .indent
        .add("ClientCalls.asyncClientStreamingCall(")
        .addIndented(
          s"channel.newCall(${method.descriptorName}, options),",
          "outputObserver"
        )
        .add(")")
        .outdent
        .add("))")
        .outdent
      case Bidirectional => printer
        .add(s"override ${serviceMethodSignature(method)} =")
        .indent
        .add(s"Flow.fromGraph(new GrpcGraphStage[${method.scalaIn}, ${method.scalaOut}](outputObserver =>")
        .indent
        .add("ClientCalls.asyncBidiStreamingCall(")
        .addIndented(
          s"channel.newCall(${method.descriptorName}, options),",
          "outputObserver"
        )
        .add(")")
        .outdent
        .add("))")
        .outdent
    }
  }

  private def bindService(service: ServiceDescriptor): PrinterEndo = _
    .add(s"def bindService(serviceImpl: ${service.name})(implicit mat: Materializer): ServerServiceDefinition =")
    .indent
    .add("ServerServiceDefinition")
    .indent
    .add(s""".builder("${service.getFullName}")""")
    .print(service.methods) { case (p, m) =>
      p.call(addMethodImplementation(m))
    }
    .add(".build()")
    .outdent
    .outdent

  private def addMethodImplementation(method: MethodDescriptor): PrinterEndo = { printer =>
    val call = method.streamType match {
      case StreamType.Unary => "ServerCalls.asyncUnaryCall"
      case StreamType.ClientStreaming => "ServerCalls.asyncClientStreamingCall"
      case StreamType.ServerStreaming => "ServerCalls.asyncServerStreamingCall"
      case StreamType.Bidirectional => "ServerCalls.asyncBidiStreamingCall"
    }
    val serverMethod = method.streamType match {
      case StreamType.Unary => s"ServerCalls.UnaryMethod[${method.scalaIn}, ${method.scalaOut}]"
      case StreamType.ClientStreaming => s"ServerCalls.ClientStreamingMethod[${method.scalaIn}, ${method.scalaOut}]"
      case StreamType.ServerStreaming => s"ServerCalls.ServerStreamingMethod[${method.scalaIn}, ${method.scalaOut}]"
      case StreamType.Bidirectional => s"ServerCalls.BidiStreamingMethod[${method.scalaIn}, ${method.scalaOut}]"
    }
    val impl: PrinterEndo = method.streamType match {
      case StreamType.ServerStreaming | StreamType.Unary => _
        .add(s"override def invoke(request: ${method.scalaIn}, responseObserver: StreamObserver[${method.scalaOut}]) =")
        .indent
        .add("Source")
        .indent
        .add(
          ".single(request)",
          s".via(serviceImpl.${method.name})",
          ".runForeach(responseObserver.onNext)",
          ".onComplete {"
        )
        .addIndented(
          "case Success(_) => responseObserver.onCompleted()",
          "case Failure(t) => responseObserver.onError(t)"
        )
        .add("}(mat.executionContext)")
        .outdent
        .outdent
      case StreamType.ClientStreaming | StreamType.Bidirectional => _
        .add(s"override def invoke(responseObserver: StreamObserver[${method.scalaOut}]): StreamObserver[${method.scalaIn}] =")
        .indent
        .add(s"reactiveSubscriberToGrpcObserver(")
        .indent
        .add("serviceImpl")
        .addIndented(
          s".${method.name}",
          ".to(Sink.fromSubscriber(grpcObserverToReactiveSubscriber(responseObserver)))",
          s".runWith(Source.asSubscriber[${method.scalaIn}])"
        )
        .outdent
        .add(")")
        .outdent
    }
    printer
      .add(".addMethod(")
      .indent
      .add(s"${method.descriptorName},")
      .add(s"$call(")
      .indent
      .add(s"new $serverMethod {")
      .indent
      .call(impl)
      .outdent
      .add("}")
      .outdent
      .add(")")
      .outdent
      .add(")")
  }


  private def javaDescriptor(service: ServiceDescriptor): PrinterEndo = { printer =>
    printer
      .add(s"def javaDescriptor: ServiceDescriptor =")
      .indent
      .add(s"${service.getFile.fileDescriptorObjectFullName}.javaDescriptor.getServices().get(${service.getIndex})")
      .outdent
  }
}