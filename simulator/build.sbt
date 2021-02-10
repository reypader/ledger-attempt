import Dependencies._

name := "simulator"

libraryDependencies ++= common

val akkaHttpVersion = "10.2.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream-kafka" % "2.0.7",
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion
)
enablePlugins(JavaAppPackaging, AshScriptPlugin, DockerPlugin)

Compile / discoveredMainClasses := Seq()
Compile / mainClass := Some("io.openledger.simulator.Application")
Docker / packageName := "simulator"
dockerBaseImage := "openjdk:11-jdk-slim"
