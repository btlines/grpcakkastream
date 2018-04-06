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
          "import _root_.akka.stream.scaladsl.Flow",
          "import _root_.com.google.protobuf.Descriptors.ServiceDescriptor",
          "import _root_.scalapb.grpc.{ AbstractService, ConcreteProtoFileDescriptorSupplier, Grpc, Marshaller, ServiceCompanion }",
          "import _root_.grpc.akkastreams._",
          "import _root_.io.grpc.{ CallOptions, Channel, MethodDescriptor, ServerServiceDefinition }",
          "import _root_.io.grpc.stub.AbstractStub"
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
        .add(s"GrpcAkkaStreamsClientCalls.unaryFlow[${method.scalaIn}, ${method.scalaOut}](")
        .addIndented(s"channel.newCall(${method.descriptorName}, options)")
        .add(")")
        .outdent
      case ServerStreaming => printer
        .add(s"override ${serviceMethodSignature(method)} =")
        .indent
        .add(s"GrpcAkkaStreamsClientCalls.serverStreamingFlow[${method.scalaIn}, ${method.scalaOut}](")
        .addIndented(s"channel.newCall(${method.descriptorName}, options)")
        .add(")")
        .outdent
      case ClientStreaming => printer
        .add(s"override ${serviceMethodSignature(method)} =")
        .indent
        .add(s"GrpcAkkaStreamsClientCalls.clientStreamingFlow[${method.scalaIn}, ${method.scalaOut}](")
        .addIndented(s"channel.newCall(${method.descriptorName}, options)")
        .add(")")
        .outdent
      case Bidirectional => printer
        .add(s"override ${serviceMethodSignature(method)} =")
        .indent
        .add(s"GrpcAkkaStreamsClientCalls.bidiStreamingFlow[${method.scalaIn}, ${method.scalaOut}](")
        .addIndented(s"channel.newCall(${method.descriptorName}, options)")
        .add(")")
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
      case StreamType.Unary => s"GrpcAkkaStreamsServerCalls.unaryCall(serviceImpl.${methodName(method)})"
      case StreamType.ClientStreaming => s"GrpcAkkaStreamsServerCalls.clientStreamingCall(serviceImpl.${methodName(method)})"
      case StreamType.ServerStreaming => s"GrpcAkkaStreamsServerCalls.serverStreamingCall(serviceImpl.${methodName(method)})"
      case StreamType.Bidirectional => s"GrpcAkkaStreamsServerCalls.bidiStreamingCall(serviceImpl.${methodName(method)})"
    }
    printer
      .add(".addMethod(")
      .addIndented(
        s"${method.descriptorName},",
        s"$call"
      )
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
