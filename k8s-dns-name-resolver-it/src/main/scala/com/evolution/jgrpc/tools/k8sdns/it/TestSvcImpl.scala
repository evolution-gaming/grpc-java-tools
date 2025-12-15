package com.evolution.jgrpc.tools.k8sdns.it

import k8sdns.it.test_svc.*

import scala.concurrent.Future

private[it] final class TestSvcImpl(instanceId: Int) extends TestSvcGrpc.TestSvc {
  override def getId(request: GetIdRequest): Future[GetIdReply] = {
    Future.successful(GetIdReply(id = instanceId))
  }
}
