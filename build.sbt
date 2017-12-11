organization in ThisBuild := "beyondthelines"
version in ThisBuild := "0.0.5-SNAPSHOT"
bintrayOrganization in ThisBuild := Some(sys.props.get("bintray.organization").getOrElse("beyondthelines"))
bintrayRepository in ThisBuild := "maven"
bintrayPackageLabels in ThisBuild := Seq("scala", "protobuf", "grpc", "akka")
licenses in ThisBuild := ("MIT", url("http://opensource.org/licenses/MIT")) :: Nil

scalaVersion in ThisBuild := "2.12.4"

lazy val runtime = (project in file("runtime"))
  .settings(
    crossScalaVersions := Seq("2.12.4", "2.11.11"),
    name := "GrpcAkkaStreamRuntime",
    libraryDependencies ++= Seq(
      "com.trueaccord.scalapb" %% "scalapb-runtime-grpc" % "0.6.7",
      "com.typesafe.akka"      %% "akka-stream"          % "2.5.4"
    )
  )

lazy val generator = (project in file("generator"))
  .settings(
    crossScalaVersions := Seq("2.12.4", "2.10.6"),
    name := "GrpcAkkaStreamGenerator",
    libraryDependencies ++= Seq(
      "com.trueaccord.scalapb" %% "compilerplugin" % "0.6.7",
      "com.trueaccord.scalapb" %% "scalapb-runtime-grpc" % "0.6.7"
    )
  )
