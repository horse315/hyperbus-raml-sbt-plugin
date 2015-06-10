organization := "eu.inn"

name := "hyperbus-cli"

version := "0.0.1"

scalaVersion := "2.11.6"

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-optimise",
  "-target:jvm-1.7",
  "-encoding", "UTF-8"
)

javacOptions ++= Seq(
  "-source", "1.7",
  "-target", "1.7",
  "-encoding", "UTF-8",
  "-Xlint:unchecked",
  "-Xlint:deprecation"
)

val akkaV = "2.3.11"

libraryDependencies ++= Seq(
  "jline" % "jline" % "2.12.1",
  "eu.inn" %% "hyperbus" % "0.0.1",
  "eu.inn" %% "servicebus-t-distributed-akka" % "0.0.1",
  "org.slf4j" % "slf4j-api" % "1.7.12",
  "com.typesafe" % "config" % "1.3.0",
  "com.github.scopt" %% "scopt" % "3.3.0",
  "ch.qos.logback" % "logback-classic" % "1.1.3",
  "org.scalatest" %% "scalatest" % "2.2.1" % "test",
  "org.mockito" % "mockito-all" % "1.10.19" % "test",
  "com.typesafe.akka" %% "akka-actor" % akkaV,
  "com.typesafe.akka" %% "akka-slf4j" % akkaV,
  "com.typesafe.akka" %% "akka-testkit" % akkaV % "test"
)

