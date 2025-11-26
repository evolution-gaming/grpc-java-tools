import Dependencies.*
import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}
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

// setting pure-Java module build settings
ThisBuild / crossPaths := false // drop off Scala suffix from artifact names.
ThisBuild / autoScalaLibrary := false // exclude scala-library from dependencies
ThisBuild / javacOptions := Seq("-source", "17", "-target", "17", "-Werror", "-Xlint:all")
ThisBuild / doc / javacOptions := Seq("-source", "17", "-Xdoclint:all", "-Werror")
ThisBuild / scalaVersion := "2.13.18"
ThisBuild / scalacOptions ++= Seq(
  "-release:17",
  "-deprecation",
  "-Xsource:3",
)

// common compile dependencies:
ThisBuild / libraryDependencies ++= Seq(
  jspecify, // JSpecify null-check annotations
)

def asJavaPublishedModule(p: Project): Project = {
  p.settings(
    // common test dependencies for Java modules:
    libraryDependencies ++= Seq(
      // to be able to run JUnit 5+ tests:
      "com.github.sbt.junit" % "jupiter-interface" % JupiterKeys.jupiterVersion.value,
      Slf4j.simple,
    ).map(_ % Test),
  )
}

def asScalaIntegrationTestModule(p: Project): Project = {
  p.disablePlugins(JupiterPlugin) // using scalatest instead
    .settings(
      publish / skip := true,
      autoScalaLibrary := true, // int tests are written in Scala, returning scala-library dependency
      Test / parallelExecution := false, // disable parallel execution between test suites
      Test / fork := true, // disable parallel execution between modules
      // tests take a long time to run, better to see the process in real time
      Test / logBuffered := false,
      // disable scaladoc generation to avoid dealing with annoying warnings
      Compile / doc / sources := Seq.empty,
      // common test dependencies for Scala int test modules:
      libraryDependencies ++= Seq(
        scalatest,
        Slf4j.simple,
      ).map(_ % Test),
    )
}

lazy val root = project.in(file("."))
  .settings(
    name := "grpc-java-tools-root",
    description := "Evolution grpc-java tools - root",
    publish / skip := true,
  )
  .aggregate(
    k8sDnsNameResolver,
    k8sDnsNameResolverIt,
  )

lazy val k8sDnsNameResolver = project.in(file("k8s-dns-name-resolver"))
  .configure(asJavaPublishedModule)
  .settings(
    name := "k8s-dns-name-resolver",
    description := "Evolution grpc-java tools - DNS-based name resolver for Kubernetes services",
    libraryDependencies ++= Seq(
      Grpc.api,
      Slf4j.api,
      dnsJava,
    ),
  )

lazy val k8sDnsNameResolverIt = project.in(file("k8s-dns-name-resolver-it"))
  .configure(asScalaIntegrationTestModule)
  // the module builds its own test app docker container
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "k8s-dns-name-resolver-it",
    description := "Evolution grpc-java tools - DNS-based name resolver for Kubernetes services - integration tests",
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb",
    ),
    dockerBaseImage := "amazoncorretto:17-alpine",
    dockerCommands ++= Seq(
      // root rights are needed to install additional packages, and also test client needs it
      // to manipulate its DNS settings
      Cmd("USER", "root"),
      // bash is needed for testcontainers log watching logic
      // lsof and coredns are needed for the integration test logic
      ExecCmd("RUN", "apk", "add", "--no-cache", "bash", "lsof", "coredns"),
    ),
    dockerExposedPorts := Seq(9000), // Should match the test app GRPC server port.
    // The int test here needs the test app docker container staged before running the code.
    // It's then used in docker compose inside testcontainers.
    test := {
      (Docker / stage).value
      (Test / test).value
    },
    libraryDependencies ++= Seq(
      Slf4j.simple,
      commonsLang3,
      "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      Testcontainers.core % Test,
    ),
  ).dependsOn(
    k8sDnsNameResolver,
  )

addCommandAlias("fmt", "all scalafmtAll scalafmtSbt javafmtAll")
addCommandAlias(
  "build",
  "all scalafmtCheckAll scalafmtSbtCheck javafmtCheckAll versionPolicyCheck Compile/doc test",
)
