import Dependencies._

name := "ledger_processor"

libraryDependencies ++= common

val akkaHttpVersion = "10.2.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.lightbend.akka" %% "akka-projection-eventsourced" % akkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-jdbc" % akkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-testkit" % akkaProjectionVersion % Test,
  "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test
)
enablePlugins(JavaAppPackaging, AshScriptPlugin, DockerPlugin)

Compile / discoveredMainClasses := Seq()
Compile / mainClass := Some("io.openledger.projection.Application")
Docker / packageName := "projection"
dockerBaseImage := "openjdk:11-jdk-slim"
dockerExposedVolumes := Seq("/opt/openledger/")
