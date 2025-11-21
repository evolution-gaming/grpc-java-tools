import Dependencies.*
import sbt.*

ThisBuild / organization := "com.evolution.jgrpc.tools"
ThisBuild / startYear := Some(2025)
ThisBuild / homepage := Some(url("https://github.com/evolution-gaming/grpc-java-tools"))
ThisBuild / licenses := Seq(("MIT", url("https://opensource.org/licenses/MIT")))
ThisBuild / organizationName := "Evolution"
ThisBuild / organizationHomepage := Some(url("https://evolution.com"))

// Maven Central requires <developers> in published pom.xml files
ThisBuild / developers := List(
  Developer(
    id = "migesok",
    name = "Mikhail Sokolov",
    email = "mikhail.g.sokolov@gmail.com",
    url = url("https://github.com/migesok"),
  ),
)

ThisBuild / scmInfo := Some(ScmInfo(
  browseUrl = url("https://github.com/evolution-gaming/grpc-java-tools"),
  connection = "git@github.com:evolution-gaming/grpc-java-tools.git",
))

// not sure if bincompat check works for Java code, put it here just in case
ThisBuild / versionPolicyIntention := Compatibility.BinaryCompatible

// this is a Java project, setting a fixed Scala version just in case
ThisBuild / scalaVersion := "2.13.16"

// setting pure-Java module build settings
ThisBuild / crossPaths := false // drop off Scala suffix from artifact names.
ThisBuild / autoScalaLibrary := false // exclude scala-library from dependencies
ThisBuild / javacOptions := Seq("-source", "17", "-target", "17", "-Werror", "-Xlint:all")
ThisBuild / doc / javacOptions := Seq("-source", "17", "-Xdoclint:all", "-Werror")

// common test dependencies:
ThisBuild / libraryDependencies ++= Seq(
  // to be able to run JUnit 5+ tests:
  "com.github.sbt.junit" % "jupiter-interface" % JupiterKeys.jupiterVersion.value,
  Slf4j.simple,
).map(_ % Test)

// common compile dependencies:
ThisBuild / libraryDependencies ++= Seq(
  jspecify, // JSpecify null-check annotations
)

lazy val root = project.in(file("."))
  .settings(
    name := "grpc-java-tools-root",
    description := "Evolution grpc-java tools - root",
    publish / skip := true,
  )
  .aggregate(
    k8sDnsNameResolver,
  )

lazy val k8sDnsNameResolver = project.in(file("k8s-dns-name-resolver"))
  .settings(
    name := "k8s-dns-name-resolver",
    description := "Evolution grpc-java tools - DNS-based name resolver for Kubernetes services",
    libraryDependencies ++= Seq(
      Grpc.api,
      Slf4j.api,
      dnsJava,
    ),
  )

addCommandAlias("fmt", "all scalafmtAll scalafmtSbt javafmtAll")
addCommandAlias(
  "build",
  "all scalafmtCheckAll scalafmtSbtCheck javafmtCheckAll versionPolicyCheck Compile/doc test",
)
