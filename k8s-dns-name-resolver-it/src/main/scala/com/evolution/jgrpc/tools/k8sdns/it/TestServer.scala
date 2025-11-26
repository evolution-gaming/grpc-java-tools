package com.evolution.jgrpc.tools.k8sdns.it

import com.evolution.jgrpc.tools.k8sdns.it.TestAppShared.TestAppSpecialLogMsgs
import io.grpc.netty.NettyServerBuilder
import k8sdns.it.test_svc.TestSvcGrpc

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext

/**
 * Server mode implementation for the `K8sDnsNameResolver` integration test service app.
 *
 * @param instanceId
 *   server instance ID which is returned by the test GRPC API
 * @see
 *   [[TestApp]]
 */
private[it] final class TestServer(instanceId: Int) {
  private val server = NettyServerBuilder
    .forPort(TestAppShared.ServerPort)
    // Connection idle timeout should be larger than potential max test case runtime, because
    // reconnect forces the client to refresh NameResolver results.
    // If this happens during K8sDnsNameResolver live DNS reload tests, it will spoil the results.
    .maxConnectionIdle(10, TimeUnit.MINUTES)
    .addService(TestSvcGrpc.bindService(new TestSvcImpl(instanceId = instanceId), ExecutionContext.global))
    .build

  def run(): Unit = {
    server.start()
    println(s"instance id: $instanceId")
    println(TestAppSpecialLogMsgs.Ready)
    server.awaitTermination()
  }
}
