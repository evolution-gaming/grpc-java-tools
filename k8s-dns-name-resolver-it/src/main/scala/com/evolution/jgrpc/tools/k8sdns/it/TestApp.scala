package com.evolution.jgrpc.tools.k8sdns.it

/**
 * `K8sDnsNameResolver` integration test service app entrypoint.
 *
 * Depending on the run mode environment variable value could either work as
 * [[TestServer]] or [[TestClient]].
 */
object TestApp extends App {
  private val runModeEnvVarName = "TEST_SVC_RUN_MODE"
  private val instanceIdVarName = "TEST_SVC_INSTANCE_ID"

  sys.env.get(runModeEnvVarName) match {
    case None =>
      sys.error(s"missing environment variable: $runModeEnvVarName")
    case Some("server") =>
      runServer()
    case Some("client") =>
      runClient()
    case Some(unexpectedRunMode) =>
      sys.error(s"unexpected run mode: $unexpectedRunMode")
  }

  private def runServer(): Unit = {
    val instanceId = sys.env.getOrElse(
      instanceIdVarName,
      sys.error(s"missing environment variable: $instanceIdVarName"),
    ).toInt

    new TestServer(instanceId = instanceId).run()
  }

  private def runClient(): Unit = {
    new TestClient().run()
  }
}
