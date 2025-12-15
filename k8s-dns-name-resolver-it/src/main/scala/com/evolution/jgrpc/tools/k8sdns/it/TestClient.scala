package com.evolution.jgrpc.tools.k8sdns.it

import com.evolution.jgrpc.tools.k8sdns.K8sDnsNameResolverProvider
import com.evolution.jgrpc.tools.k8sdns.it.TestAppShared.*
import io.grpc.netty.NettyChannelBuilder
import k8sdns.it.test_svc.TestSvcGrpc.TestSvcBlockingStub
import k8sdns.it.test_svc.{GetIdRequest, TestSvcGrpc}
import org.apache.commons.lang3.exception.ExceptionUtils

import java.io.IOException
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.net.{InetAddress, URI}
import java.nio.file.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*
import scala.sys.process
import scala.sys.process.ProcessLogger

/**
 * Client mode implementation for the `K8sDnsNameResolver` integration test service app.
 *
 * It is controlled by the test code using [[TestClientControl]].
 *
 * The results are reported back using special log messages: [[TestAppSpecialLogMsgs]].
 *
 * Supported test cases: [[TestClientTestCase]].
 *
 * DNS record changes are done using a controlled CoreDNS instance run in the test client
 * app container.
 *
 * @see
 *   [[TestApp]]
 */
private[it] final class TestClient {
  import TestClient.*

  def run(): Unit = {
    try {
      val controlCommandWatcher = new ControlCommandWatcher
      println(TestAppSpecialLogMsgs.Ready)
      val fixture = new Fixture
      runTestCaseLoop(controlCommandWatcher, fixture)
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        println(TestAppSpecialLogMsgs.ClientPrematureDeath)
        sys.exit(1)
    }
  }

  private def runTestCaseLoop(controlCommandWatcher: ControlCommandWatcher, fixture: Fixture): Unit = {
    while (true) {
      controlCommandWatcher.waitForCommand() match {
        case TestClientControl.RunTestCaseCmdFileName(testCase) =>
          runTestCase {
            testCase match {
              case TestClientTestCase.DiscoverNewPod =>
                testCaseDiscoverNewPod(fixture)
              case TestClientTestCase.DnsFailureRecover =>
                testCaseDnsFailureRecover(fixture)
            }
          }

        case unknownCmd: String =>
          sys.error(s"unknown test client control command: $unknownCmd")
      }
    }
  }

  private def runTestCase(body: => Unit): Unit = {
    try {
      body
      println(TestAppSpecialLogMsgs.ClientTestCaseSuccess)
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        println(TestAppSpecialLogMsgs.ClientTestCaseFailed)
    }
  }

  private def testCaseDiscoverNewPod(fixture: Fixture): Unit = {
    fixture.coreDns.ensureStarted(serviceIps = Set(fixture.srv1Ip))

    withRoundRobinLbClient { client =>
      callHost2TimesAssertServerIds(client, expectedServerIds = Set(1))

      fixture.coreDns.setServiceIps(Set(fixture.srv1Ip, fixture.srv2Ip))

      sleepUntilClientGetsDnsUpdate()

      callHost2TimesAssertServerIds(client, expectedServerIds = Set(1, 2))
    }
  }

  private def testCaseDnsFailureRecover(fixture: Fixture): Unit = {
    fixture.coreDns.ensureStarted(serviceIps = Set(fixture.srv1Ip))

    withRoundRobinLbClient { client =>
      callHost2TimesAssertServerIds(client, expectedServerIds = Set(1))

      fixture.coreDns.ensureStopped()

      sleepUntilClientGetsDnsUpdate()

      fixture.coreDns.ensureStarted(serviceIps = Set(fixture.srv1Ip, fixture.srv2Ip))

      sleepUntilClientGetsDnsUpdate()

      callHost2TimesAssertServerIds(client, expectedServerIds = Set(1, 2))
    }
  }

  private def sleepUntilClientGetsDnsUpdate(): Unit = {
    val sleepIntervalSeconds =
      coreDnsHostsReloadIntervalSeconds +
        K8sDnsNameResolverProvider.DEFAULT_REFRESH_INTERVAL_SECONDS +
        2 // adding 2 seconds on top just in case

    println(s"Sleeping until GRPC client gets DNS update: $sleepIntervalSeconds seconds")
    Thread.sleep(sleepIntervalSeconds.toLong * 1000)
  }

  private def callHost2TimesAssertServerIds(
    client: TestSvcBlockingStub,
    expectedServerIds: Set[Int],
  ): Unit = {
    val actualServerIds = 0.until(2).map { _ =>
      client.getId(GetIdRequest()).id
    }.toSet
    if (actualServerIds != expectedServerIds) {
      sys.error(s"GRPC client observed server IDs $actualServerIds, expected $expectedServerIds")
    }
  }

  private def withRoundRobinLbClient[T](body: TestSvcBlockingStub => T): T = {
    val channel = NettyChannelBuilder
      .forTarget(s"k8s-dns://$svcHostname:${ TestAppShared.ServerPort }")
      .usePlaintext()
      .defaultLoadBalancingPolicy("round_robin")
      .build()

    try {
      body(TestSvcGrpc.blockingStub(channel))
    } finally {
      channel.shutdownNow()
      // setting some termination just in case something gets stuck
      channel.awaitTermination(10L, TimeUnit.SECONDS)
      ()
    }
  }
}

private object TestClient {
  private val svcHostname: String = "svc.example.org"
  private val resolveConfPath = "/etc/resolv.conf"

  private val coreDnsCoreFilePath = "/etc/coredns/CoreFile"
  private val coreDnsHostsFilePath = "/etc/coredns/hosts"
  private val coreDnsHealthEndpointPort = 8080
  private val coreDnsReadyTimeout = 10.seconds
  private val coreDnsReadyCheckAttemptDelay = 2.seconds
  private val coreDnsHostsReloadIntervalSeconds = 2

  private final class Fixture {
    val srv1Ip: String = InetAddress.getByName(TestAppSvcNames.Server1).getHostAddress
    val srv2Ip: String = InetAddress.getByName(TestAppSvcNames.Server2).getHostAddress
    val coreDns: CoreDns = new CoreDns
    setCoreDnsAsPrimaryDns()
  }

  private def setCoreDnsAsPrimaryDns(): Unit = {
    Files.write(
      Paths.get(resolveConfPath),
      Vector(
        "nameserver 127.0.0.1",
      ).asJava,
      StandardOpenOption.TRUNCATE_EXISTING,
    )
    ()
  }

  private final class ControlCommandWatcher {
    Files.createDirectories(TestClientControl.CmdDirPath)
    private val watchService = FileSystems.getDefault.newWatchService
    TestClientControl.CmdDirPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE)

    private var commandStash: Vector[String] = Vector.empty

    @tailrec
    def waitForCommand(): String = {
      if (commandStash.nonEmpty) {
        val cmd = commandStash.head
        commandStash = commandStash.drop(1)
        cmd
      } else {
        val watchKey = watchService.take()
        val createdFilesRelativePaths =
          watchKey.pollEvents().asScala.map(e => e.context().asInstanceOf[Path]).toVector
        createdFilesRelativePaths.foreach(relativePath =>
          Files.delete(TestClientControl.CmdDirPath.resolve(relativePath)),
        )
        watchKey.reset()
        commandStash = createdFilesRelativePaths.map(_.toString)
        waitForCommand()
      }
    }
  }

  private final class CoreDns {
    private val healthCheckHttpClient = HttpClient.newHttpClient()

    private var currentIpSet: Set[String] = Set.empty

    writeCoreFile()
    writeHostsFile(ips = currentIpSet)

    private var processOpt: Option[CoreDns.StartedProcess] = None

    private def writeCoreFile(): Unit = {
      Files.writeString(
        Paths.get(coreDnsCoreFilePath),
        s"""$svcHostname {
           |    hosts $coreDnsHostsFilePath {
           |        ttl $coreDnsHostsReloadIntervalSeconds
           |        reload ${ coreDnsHostsReloadIntervalSeconds }s
           |    }
           |    errors         # show errors
           |    log            # enable query logs
           |    health :$coreDnsHealthEndpointPort   # enable healthcheck HTTP endpoint
           |}
           |""".stripMargin,
      )
      ()
    }

    private def callIsHealthy(): Either[String, Unit] = {
      val request = HttpRequest.newBuilder()
        .uri(new URI(s"http://127.0.0.1:$coreDnsHealthEndpointPort/health"))
        .timeout(coreDnsReadyCheckAttemptDelay.toJava)
        .GET()
        .build()
      try {
        val response = healthCheckHttpClient.send(request, BodyHandlers.ofString())
        if (response.statusCode() == 200) {
          Right(())
        } else {
          Left(s"status code not OK but ${ response.statusCode() }")
        }
      } catch {
        case e: IOException =>
          Left(ExceptionUtils.getMessage(e))
      }
    }

    @tailrec
    private def waitUntilHealthy(
      startTime: Instant = Instant.now(),
    ): Unit = {
      callIsHealthy() match {
        case Right(_) =>
          println("CoreDNS process is ready")
        case Left(err) =>
          println(s"CoreDNS process is not ready yet: $err")
          val timePassed = java.time.Duration.between(startTime, Instant.now())
          if (timePassed.toScala > coreDnsReadyTimeout) {
            sys.error(s"CoreDNS process startup timed out after $coreDnsReadyTimeout")
          } else {
            Thread.sleep(coreDnsReadyCheckAttemptDelay.toMillis)
            waitUntilHealthy(startTime)
          }
      }
    }

    def ensureStarted(serviceIps: Set[String]): Unit = {
      processOpt match {
        case Some(process) if process.sysProcess.isAlive() && serviceIps == currentIpSet =>
          waitUntilHealthy()
        case _ =>
          // stopping first to make sure we get new DNS records without waiting
          // for the hosts file reload
          ensureStopped()
          setServiceIps(serviceIps)
          startNewProcess()
      }
    }

    def ensureStopped(): Unit = {
      processOpt match {
        case Some(process) if process.sysProcess.isAlive() =>
          println("stopping CoreDNS process")
          process.sysProcess.destroy()
        case _ =>
      }
      processOpt = None
    }

    private def startNewProcess(): Unit = {
      processOpt = None
      println("starting CoreDNS process")
      val process = sys.process.Process(s"coredns -conf $coreDnsCoreFilePath").run(
        ProcessLogger(line => println(s"[CoreDNS] $line")),
      )
      // CoreDNS usually needs ~100ms to start, so the first health check attempt is almost
      // guaranteed to fail.
      // Let's wait a bit to allow it to start.
      Thread.sleep(500L)
      waitUntilHealthy()
      val pid = findDnsProcessPid().getOrElse(
        sys.error("unable to get CoreDNS process PID - nothing bound to port 53"),
      )
      println(s"CoreDNS process PID: $pid")
      processOpt = Some(new CoreDns.StartedProcess(
        pid = pid,
        sysProcess = process,
      ))
    }

    private def findDnsProcessPid(): Option[Int] = {
      val lsOfOutRaw = sys.process.Process("lsof -t -i:53").!!
      Some(lsOfOutRaw.strip()).filter(_.nonEmpty).map { lsOfOut =>
        lsOfOut.toIntOption.getOrElse(sys.error(s"malformed lsof output - $lsOfOut"))
      }
    }

    private def writeHostsFile(ips: Set[String]): Unit = {
      // overwriting hosts file atomically so CoreDNS couldn't observe broken file content

      val tmpHostsFile = Files.createTempFile("hosts", "txt")
      Files.write(
        tmpHostsFile,
        ips.toVector.sorted.map(ip => s"$ip $svcHostname").asJava,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
      )

      Files.move(tmpHostsFile, Paths.get(coreDnsHostsFilePath), StandardCopyOption.ATOMIC_MOVE)
      ()
    }

    def setServiceIps(ips: Set[String]): Unit = {
      if (ips != currentIpSet) {
        writeHostsFile(ips)
        currentIpSet = ips
      }
      // it looks like CoreDNS hosts plugin does not reread files on SIGHUP
      // force CoreDNS to reread configs just to be sure
//      processOpt.foreach(p => sys.process.Process(s"kill -HUP ${ p.pid }").!!)
    }
  }

  private object CoreDns {
    private final class StartedProcess(
      val pid: Int,
      val sysProcess: process.Process,
    )
  }
}
