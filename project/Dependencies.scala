import sbt.*

object Dependencies {
  val dnsJava = "dnsjava" % "dnsjava" % "3.6.3"
  val jspecify = "org.jspecify" % "jspecify" % "1.0.0"

  object Grpc {
    // let's keep it in sync with the version used by the last release of scalapb
    private val version = "1.62.2"

    val api = "io.grpc" % "grpc-api" % version
  }

  object Slf4j {
    private val version = "2.0.17"

    val api = "org.slf4j" % "slf4j-api" % version
    val simple = "org.slf4j" % "slf4j-simple" % version
  }
}
