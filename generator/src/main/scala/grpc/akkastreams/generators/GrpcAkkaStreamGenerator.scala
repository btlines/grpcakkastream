package grpc.akkastreams.generators

import com.google.protobuf.Descriptors._
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos.{CodeGeneratorRequest, CodeGeneratorResponse}
import scalapb.compiler.FunctionalPrinter.PrinterEndo
import scalapb.compiler.StreamType.{Bidirectional, ClientStreaming, ServerStreaming, Unary}
import scalapb.compiler._
import scalapb.options.compiler.Scalapb

import scala.collection.JavaConverters._

object GrpcAkkaStreamGenerator {
  def apply(flatPackage: Boolean = false): GrpcAkkaStreamGenerator = {
    val params = GeneratorParams().copy(flatPackage = flatPackage)
    new GrpcAkkaStreamGenerator(params)
  }
}

class GrpcAkkaStreamGenerator(override val params: GeneratorParams)
  extends protocbridge.ProtocCodeGenerator
  with DescriptorPimps {

  override def run(requestBytes: Array[Byte]): Array[Byte] = {
    // Read scalapb.options (if present) in .proto files
    val registry = ExtensionRegistry.newInstance()
    Scalapb.registerAllExtensions(registry)

    val b = CodeGeneratorResponse.newBuilder
    val request = CodeGeneratorRequest.parseFrom(requestBytes, registry)

    val fileDescByName: Map[String, FileDescriptor] =
      request.getProtoFileList.asScala.foldLeft[Map[String, FileDescriptor]](Map.empty) {
        case (acc, fp) =>
          val deps = fp.getDependencyList.asScala.map(acc)
          acc + (fp.getName -> FileDescriptor.buildFrom(fp, deps.toArray))
      }

    request.getFileToGenerateList.asScala.foreach {
      name =>
        val fileDesc = fileDescByName(name)
        val responseFiles = generateFiles(fileDesc)
        b.addAllFile(responseFiles.asJava)
    }
    b.build.toByteArray
  }

  def generateFiles(fileDesc: FileDescriptor): Seq[CodeGeneratorResponse.File] = {
    fileDesc.getServices.asScala.map { service =>
      val b = CodeGeneratorResponse.File.newBuilder()
      val objectName = s"${service.objectName}AkkaStream"
      b.setName(s"${fileDesc.scalaDirectory}/$objectName.scala")

      val fp = FunctionalPrinter()
        .add(s"package ${fileDesc.scalaPackageName}")
        .newline
        .add(
          "import _root_.akka.NotUsed",
          "import _root_.akka.stream.Materializer",
          "import _root_.akka.stream.scaladsl.{ Flow, Sink, Source }",
          "import _root_.com.google.protobuf.Descriptors.ServiceDescriptor",
          "import _root_.scalapb.grpc.{ AbstractService, ConcreteProtoFileDescriptorSupplier, Grpc, Marshaller, ServiceCompanion }",
          "import _root_.grpc.akkastreams.GrpcAkkaStreams._",
          "import _root_.io.grpc.{ CallOptions, Channel, MethodDescriptor, ServerServiceDefinition }",
          "import _root_.io.grpc.stub.{ AbstractStub, ClientCalls, ServerCalls, StreamObserver }",
          "import _root_.org.reactivestreams.{ Publisher, Subscriber }",
          "import _root_.scala.concurrent.Await",
          "import _root_.scala.concurrent.duration._",
          "import _root_.scala.util.{ Failure, Success }"
        )
        .newline
        .add(s"object $objectName {")
        .indent
        .print(service.getMethods.asScala) {
          case (p, m) => p.call(serviceMethodDescriptor(m))
        }
        .newline
        .call(serviceDescriptor(service))
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
        .outdent
        .add("}")
        .newline

      b.setContent(fp.result)
      b.build
    }
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
      .add("MethodDescriptor.newBuilder()")
      .addIndented(
        s".setType(MethodDescriptor.MethodType.$methodType)",
        s""".setFullMethodName(MethodDescriptor.generateFullMethodName("${method.getService.getFullName}", "${method.getName}"))""",
        s".setRequestMarshaller(new Marshaller(${method.scalaIn}))",
        s".setResponseMarshaller(new Marshaller(${method.scalaOut}))",
        ".build()"
      )
      .outdent
  }

  private def serviceDescriptor(service: ServiceDescriptor): PrinterEndo =
    _.add(s"""val SERVICE: _root_.io.grpc.ServiceDescriptor = _root_.io.grpc.ServiceDescriptor.newBuilder("${service.getFullName}")""")
      .indent
      .add(s".setSchemaDescriptor(new ConcreteProtoFileDescriptorSupplier(${service.getFile.fileDescriptorObjectFullName}.javaDescriptor))")
      .print(service.methods) { case (printer, method) =>
        printer.add(s".addMethod(${method.descriptorName})")
      }
      .add(".build()")
      .outdent

  private def serviceTrait(service: ServiceDescriptor): PrinterEndo = _
    .add(s"trait ${service.getName} extends AbstractService {")
    .indent
    .add(s"override def serviceCompanion = ${service.getName}")
    .seq(service.methods.map(serviceMethodSignature))
    .outdent
    .add("}")

  private def methodName(method: MethodDescriptor): String = {
    // These five methods are defined in `AnyRef` and will cause a name clash if
    // we do not rename them.
    if (Seq("clone", "finalize", "notify", "notifyAll", "wait") contains method.name)
      s"${method.name}_ "
    else
      method.name
  }

  private def serviceMethodSignature(method: MethodDescriptor): String =
    s"def ${methodName(method)}: Flow[${method.scalaIn}, ${method.scalaOut}, NotUsed]"

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
        .add(s"Flow.fromGraph(")
        .indent
        .add(s"new GrpcGraphStage[${method.scalaIn}, ${method.scalaOut}]({ outputObserver =>")
        .indent
        .add(s"new StreamObserver[${method.scalaIn}] {")
        .indent
        .add(
          "override def onError(t: Throwable): Unit = ()",
          "override def onCompleted(): Unit = ()",
          s"override def onNext(request: ${method.scalaIn}): Unit ="
        )
        .indent
        .add("ClientCalls.asyncServerStreamingCall(")
        .addIndented(
          s"channel.newCall(${method.descriptorName}, options),",
          "request,",
          "outputObserver"
        )
        .add(")")
        .outdent
        .outdent
        .add("}")
        .outdent
        .add("})")
        .outdent
        .add(")")
        .outdent
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
    .add(".builder(SERVICE)")
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
          s".via(serviceImpl.${methodName(method)})",
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
          .add("override def invoke(")
          .addIndented(s"responseObserver: StreamObserver[${method.scalaOut}]")
          .add(s"): StreamObserver[${method.scalaIn}] =")
          .indent
          .add(
            "// blocks until the GraphStage is fully initialized",
            "Await.result("
          )
          .indent
          .add("Source")
          .addIndented(
            s".fromGraph(new GrpcSourceStage[${method.scalaIn}])",
            s".via(serviceImpl.${methodName(method)})",
            ".to(Sink.fromSubscriber(grpcObserverToReactiveSubscriber(responseObserver)))",
            ".run(),"
          )
          .add("5.seconds")
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
