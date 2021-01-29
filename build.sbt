name := "open-ledger"

version := "1.0"

scalaVersion := "2.13.1"

lazy val akkaVersion = "2.6.11"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.1.0" % Test
)
