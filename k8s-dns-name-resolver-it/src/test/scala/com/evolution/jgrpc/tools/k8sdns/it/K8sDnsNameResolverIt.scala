package com.evolution.jgrpc.tools.k8sdns.it

import com.evolution.jgrpc.tools.k8sdns.it.TestAppShared.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.slf4j.LoggerFactory
import org.testcontainers.containers.ComposeContainer
import org.testcontainers.containers.output.OutputFrame
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy

import java.io.File
import java.nio.file.Paths
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import java.util.regex.Pattern
import scala.concurrent.*
import scala.concurrent.duration.*
import scala.jdk.FunctionConverters.*
import scala.util.control.NoStackTrace

/**
 * Integration tests for `K8sDnsNameResolver`.
 *
 * Uses testcontainers with docker compose (`src/test/resources/docker/compose-test.yml`)
 * to run 2 instances of a GRPC server and one instance of a GRPC client with
 * `K8sDnsNameResolver` and a controlled DNS server.
 *
 * Then it verifies how the client reacts to changes in DNS for the service hostname.
 *
 * Test cases are run on the client app container.
 *
 * The test code communicates with the client container using files in a special command
 * folder (see [[TestClientControl]]).
 *
 * The test results are reported back using log messages (see [[TestAppSpecialLogMsgs]]).
 */
class K8sDnsNameResolverIt extends AnyFreeSpec with BeforeAndAfterAll {
  import K8sDnsNameResolverIt.*

  // should be enough to start/stop CoreDNS and wait for K8sDnsNameResolver reload several times
  // over
  private val testCaseTimeout = 2.minutes

  private val logger = LoggerFactory.getLogger(classOf[K8sDnsNameResolverIt])

  private val testClientLogWatcher = new TestClientLogWatcher

  private val composeContainer: ComposeContainer = new ComposeContainer(
    classpathResourceToFile("/docker/compose-test.yml"),
  )
    .waitingFor(TestAppSvcNames.Server1, testAppReadyWaitStrategy)
    .waitingFor(TestAppSvcNames.Server2, testAppReadyWaitStrategy)
    .waitingFor(TestAppSvcNames.Client, testAppReadyWaitStrategy)
    .withLogConsumer1(TestAppSvcNames.Server1, printingLogConsumer)
    .withLogConsumer1(TestAppSvcNames.Server2, printingLogConsumer)
    .withLogConsumer1(TestAppSvcNames.Client, printingLogConsumer)
    .withLogConsumer(TestAppSvcNames.Client, testClientLogWatcher)

  override def beforeAll(): Unit = {
    composeContainer.start()
  }

  override def afterAll(): Unit = {
    composeContainer.stop()
  }

  "K8sDnsNameResolver" - {
    "should discover a new pod after a DNS A-record added" in {
      runTestCase(TestClientTestCase.DiscoverNewPod)
    }

    "should recover after DNS query failure" in {
      runTestCase(TestClientTestCase.DnsFailureRecover)
    }
  }

  private def runTestCase(testCase: TestClientTestCase): Unit = {
    val resultFuture = testClientLogWatcher.subscribeForTestCaseResult()

    runInContainerExpectSuccess(
      svcName = TestAppSvcNames.Client,
      cmd = Vector(
        "touch",
        s"${ TestClientControl.CmdDirPath }/${ TestClientControl.RunTestCaseCmdFileName(testCase) }",
      ),
    )

    Await.result(resultFuture, testCaseTimeout)
  }

  private def runInContainerExpectSuccess(svcName: String, cmd: Vector[String]): Unit = {
    val result = composeContainer.getContainerByServiceName(svcName).orElseThrow()
      .execInContainer(cmd*)
    if (result.getExitCode != 0) {
      val stdOut = Option(result.getStdout).filter(_.nonEmpty).getOrElse("EMPTY")
      val stdErr = Option(result.getStderr).filter(_.nonEmpty).getOrElse("EMPTY")
      sys.error(
        s"[$svcName] container command failed: code ${ result.getExitCode }\n\tstdout: $stdOut\n\tstderr: $stdErr",
      )
    }
  }

  private def testAppReadyWaitStrategy: LogMessageWaitStrategy = {
    new LogMessageWaitStrategy().withRegEx(
      s"${ Pattern.quote(TestAppSpecialLogMsgs.Ready) }.+",
    )
  }

  // when a test case fails, these logs are the only way to find what went wrong
  private def printingLogConsumer(serviceName: String): LogConsumer = (outputFrame: OutputFrame) => {
    outputFrame.getType match {
      case OutputFrame.OutputType.STDOUT | OutputFrame.OutputType.STDERR =>
        logger.info(s"[$serviceName] ${ outputFrame.getUtf8StringWithoutLineEnding }")
      case OutputFrame.OutputType.END =>
        // testcontainers API didn't provide a way to distinguish close markers for stdout and
        // stderr, so this line is printed twice
        logger.info(s"[$serviceName] STDOUT/STDERR END")
    }
  }

  private def classpathResourceToFile(resource: String): File = {
    Paths.get(getClass.getResource(resource).toURI).toFile
  }
}

private object K8sDnsNameResolverIt {
  type LogConsumer = OutputFrame => Unit

  private implicit class RichComposeContainer(val inner: ComposeContainer) extends AnyVal {
    def withLogConsumer1(serviceName: String, mkConsumer: String => LogConsumer): ComposeContainer = {
      inner.withLogConsumer(serviceName, mkConsumer(serviceName).asJava)
    }
  }

  /**
   * Stateful, thread-safe component to subscribe to [[TestClient]] special log messages,
   * which indicate results of test case executions.
   *
   * @see
   *   [[TestAppSpecialLogMsgs]]
   */
  private final class TestClientLogWatcher extends Consumer[OutputFrame] {

    private val stateLock: ReentrantLock = new ReentrantLock()

    private var testClientDiedPrematurely: Boolean = false
    private var watcherOpt: Option[Promise[Unit]] = None

    override def accept(frame: OutputFrame): Unit = {
      frame.getType match {
        case OutputFrame.OutputType.STDOUT | OutputFrame.OutputType.STDERR =>
          val msgStr = frame.getUtf8String

          if (msgStr.contains(TestAppSpecialLogMsgs.ClientTestCaseSuccess)) {
            underStateLock {
              watcherOpt.foreach(_.success(()))
              watcherOpt = None
            }
          } else if (msgStr.contains(TestAppSpecialLogMsgs.ClientTestCaseFailed)) {
            underStateLock {
              watcherOpt.foreach(_.failure(mkRemoteTestCaseFailed))
              watcherOpt = None
            }
          } else if (msgStr.contains(TestAppSpecialLogMsgs.ClientPrematureDeath)) {
            underStateLock {
              testClientDiedPrematurely = true
              watcherOpt.foreach(_.failure(mkRemoteTestCaseFailedPrematureDeath))
              watcherOpt = None
            }
          }

        case OutputFrame.OutputType.END =>
          ()
      }
    }

    def subscribeForTestCaseResult(): Future[Unit] = underStateLock {
      if (testClientDiedPrematurely) {
        Future.failed(mkRemoteTestCaseFailedPrematureDeath)
      } else if (watcherOpt.nonEmpty) {
        Future.failed(mkRemoteTestCaseFailed)
      } else {
        val promise = Promise[Unit]()
        watcherOpt = Some(promise)
        promise.future
      }
    }

    private def underStateLock[T](body: => T): T = {
      stateLock.lock()
      try {
        body
      } finally {
        stateLock.unlock()
      }
    }
  }

  private def mkRemoteTestCaseFailedPrematureDeath = RemoteTestCaseException(
    "test client app container died prematurely",
  )

  private def mkRemoteTestCaseFailed = RemoteTestCaseException(
    s"test case failed",
  )

  private case class RemoteTestCaseException(
    details: String,
  ) extends RuntimeException(
    s"$details\ncheck printed ${ TestAppSvcNames.Client } logs for more info",
  ) with NoStackTrace
}
