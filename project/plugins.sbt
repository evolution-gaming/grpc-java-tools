addSbtPlugin("com.evolution" % "sbt-scalac-opts-plugin" % "0.0.9")
addSbtPlugin("com.github.sbt" % "sbt-java-formatter" % "0.10.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.5")
addSbtPlugin("ch.epfl.scala" % "sbt-version-policy" % "3.2.1")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")
// to be able to run JUnit 5+ tests:
addSbtPlugin("com.github.sbt.junit" % "sbt-jupiter-interface" % "0.17.0")
// for docker-compose-based integration tests:
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.4")
