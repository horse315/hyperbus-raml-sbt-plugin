organization := "eu.inn"

name := "hyperbus"

version := "0.0.1"

scalaVersion := "2.11.6"

scalacOptions ++= Seq(
  "-language:postfixOps",
  "-language:implicitConversions",
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

libraryDependencies ++= Seq(
  "eu.inn" %% "binders-core" % "0.5.12",
  "eu.inn" %% "binders-json" % "0.5.12",
  "org.slf4j" % "slf4j-api" % "1.7.12"
)

val paradiseVersion = "2.1.0-M5"

addCompilerPlugin("org.scalamacros" % "paradise" % paradiseVersion cross CrossVersion.full)
