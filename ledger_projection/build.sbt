import Dependencies._

name := "ledger_projection"

libraryDependencies ++= common

val akkaHttpVersion = "10.2.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
  "com.lightbend.akka" %% "akka-projection-eventsourced" % akkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-jdbc" % akkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-testkit" % akkaProjectionVersion % Test
)
enablePlugins(JavaAppPackaging, AshScriptPlugin, DockerPlugin)

Compile / discoveredMainClasses := Seq()
Compile / mainClass := Some("io.openledger.projection.Application")
Docker / packageName := "projection"
dockerBaseImage := "openjdk:11-jdk-slim"
dockerExposedPorts := Seq(8558, 25520)
dockerExposedVolumes := Seq("/opt/openledger/")
