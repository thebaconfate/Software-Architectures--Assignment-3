
ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.1"

lazy val root = (project in file("."))
  .settings(
    name := "Software-Architectures--Assignment-3",
    idePackagePrefix := Some("assignment")
  )

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.8.5",
  "org.slf4j" % "slf4j-simple" % "2.0.9"
)