import Dependencies._

name := "ledger_processor"

libraryDependencies ++= common

val akkaHttpVersion = "10.2.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream-kafka" % "2.0.7",
  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test
)
enablePlugins(JavaAppPackaging, AshScriptPlugin, DockerPlugin)

Compile / PB.protoSources := Seq(file("./protobuf/"))
Compile / PB.targets := Seq(
  scalapb.gen(singleLineToProtoString = true, asciiFormatToString = true) -> (Compile / sourceManaged).value
)
Compile / discoveredMainClasses := Seq()
Compile / mainClass := Some("io.openledger.Application")
Docker / packageName := "openledger"
dockerBaseImage := "openjdk:11-jdk-slim"
dockerExposedPorts := Seq(8080,8558,25520)
dockerExposedVolumes := Seq("/opt/openledger/")