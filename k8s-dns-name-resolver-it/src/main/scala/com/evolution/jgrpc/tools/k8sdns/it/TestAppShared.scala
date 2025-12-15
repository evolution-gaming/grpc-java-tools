package com.evolution.jgrpc.tools.k8sdns.it

import java.nio.file.*

/**
 * Common things shared between the `K8sDnsNameResolver` integration test code and the
 * test service code
 *
 * @see
 *   [[TestApp]]
 */
object TestAppShared {

  /**
   * [[TestApp]] GRPC server port
   */
  val ServerPort: Int = 9000

  /**
   * Docker compose service names for [[TestApp]] containers.
   *
   * The names here should match the ones used in the
   * `src/test/resources/docker/compose-test.yml` file.
   */
  object TestAppSvcNames {

    /**
     * First [[TestApp]] service in a [[TestServer]] mode
     */
    val Server1: String = "test-server1"

    /**
     * Second [[TestApp]] service in a [[TestServer]] mode
     */
    val Server2: String = "test-server2"

    /**
     * [[TestApp]] service in a [[TestClient]] mode
     */
    val Client: String = "test-client"
  }

  /**
   * `K8sDnsNameResolver` integration test watches for these [[TestApp]] log messages in
   * the stdout.
   */
  object TestAppSpecialLogMsgs {

    /**
     * [[TestApp]] docker container has been started and ready to proceed with the test
     */
    val Ready: String = "TEST CONTAINER READY"

    /**
     * [[TestApp]] in the [[TestClient]] mode died prematurely, all the tests should be
     * aborted
     */
    val ClientPrematureDeath: String = "TEST CLIENT PANIC"

    /**
     * [[TestApp]] in the [[TestClient]] mode completed a requested test case successfully
     *
     * @see
     *   [[TestClientControl]] for how to request a test case execution
     */
    val ClientTestCaseSuccess: String = "TEST SUCCESS"

    /**
     * [[TestApp]] in the [[TestClient]] mode ran a requested test case and got a failure
     *
     * @see
     *   [[TestClientControl]] for how to request a test case execution
     */
    val ClientTestCaseFailed: String = "TEST FAILED"
  }

  /**
   * Defines the way to send commands to the [[TestApp]] container in the [[TestClient]]
   * mode:
   *   - create an empty file in the [[CmdDirPath]] directory on the container - the name
   *     of the file is the command name
   *   - the [[TestClient]] code deletes the file and queues the command for execution
   *   - commands are executed on the [[TestClient]] one-by-one
   *   - monitor [[TestClient]] container stdout for the command progress - see
   *     [[TestAppSpecialLogMsgs]]
   *
   * Currently supported commands:
   *   - [[RunTestCaseCmdFileName]] for running [[TestClientTestCase]]
   */
  object TestClientControl {

    /**
     * Directory which [[TestApp]] in the [[TestClient]] mode uses for receiving commands
     *
     * @see
     *   [[TestClientControl]]
     */
    val CmdDirPath: Path = Paths.get("/tmp/test-client-control")

    /**
     * [[TestClientControl]] command for running [[TestClientTestCase]].
     */
    object RunTestCaseCmdFileName {
      private val fileNamePrefix = ".run-test-case-"

      /**
       * Creates a [[TestClientControl]] command file name for running the given
       * [[TestClientTestCase]]
       */
      def apply(testCase: TestClientTestCase): String = {
        s"$fileNamePrefix${ testCase.name }"
      }

      /**
       * Matches [[TestClientControl]] command file name which runs a
       * [[TestClientTestCase]]
       */
      def unapply(fileName: String): Option[TestClientTestCase] = {
        if (fileName.startsWith(fileNamePrefix)) {
          val testCaseName = fileName.drop(fileNamePrefix.length)
          TestClientTestCase.values.find(_.name == testCaseName)
        } else {
          None
        }
      }
    }
  }

  /**
   * Test case to run on [[TestClient]].
   *
   * @see
   *   [[TestClientControl.RunTestCaseCmdFileName]]
   */
  sealed abstract class TestClientTestCase extends Product {
    final def name: String = productPrefix
  }
  object TestClientTestCase {
    val values: Vector[TestClientTestCase] = Vector(
      DiscoverNewPod,
      DnsFailureRecover,
    )

    /**
     * [[TestClient]] test case verifying that `K8sDnsNameResolver` live pod discovery
     * works.
     *
     * Test steps overview:
     *   - point the service host DNS records to one server container
     *   - create a GRPC client, check that it sees only the first server
     *   - add the second server to the DNS records
     *   - check that after the configured reload TTL, the client sees both servers
     */
    case object DiscoverNewPod extends TestClientTestCase

    /**
     * [[TestClient]] test case verifying that `K8sDnsNameResolver` recovers after a DNS
     * call failure.
     *
     * Test steps overview:
     *   - point the service host DNS records to one server container
     *   - create a GRPC client, check that it sees only the first server
     *   - stop the DNS server, wait until the client gets a DNS error
     *   - start the DNS server back again, with 2 servers in the records
     *   - check that after the configured reload TTL, the client sees both servers
     */
    case object DnsFailureRecover extends TestClientTestCase
  }
}
