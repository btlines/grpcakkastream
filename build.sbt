lazy val commonSettings = Seq(
  organization := "beyondthelines",
  version := "0.0.2",
  licenses := ("MIT", url("http://opensource.org/licenses/MIT")) :: Nil,
  bintrayOrganization := Some("beyondthelines"),
  bintrayPackageLabels := Seq("scala", "protobuf", "grpc", "akka")
)

lazy val runtime = (project in file("runtime"))
  .settings(
    commonSettings,
    scalaVersion := "2.12.2",
    crossScalaVersions := Seq("2.12.2", "2.11.11"),
    name := "GrpcAkkaStreamRuntime",
    libraryDependencies ++= Seq(
      "com.trueaccord.scalapb" %% "scalapb-runtime-grpc" % "0.6.0-pre5",
      "com.typesafe.akka"      %% "akka-stream"          % "2.5.4"
    )
  )

lazy val generator = (project in file("generator"))
  .settings(
    commonSettings,
    scalaVersion := "2.10.6",
    name := "GrpcAkkaStreamGenerator",
    libraryDependencies ++= Seq(
      "com.trueaccord.scalapb" %% "compilerplugin" % "0.6.0-pre5"
    )
  )
