/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.net.ServerSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import linea.domain.BlockParameter
import linea.ethapi.EthApiClient
import linea.web3j.ethapi.createEthApiClient
import maru.database.BeaconChain
import maru.p2p.messages.BeaconBlocksByRangeHandler
import org.apache.logging.log4j.LogManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.hyperledger.besu.tests.acceptance.dsl.blockchain.Amount
import org.hyperledger.besu.tests.acceptance.dsl.condition.net.NetConditions
import org.hyperledger.besu.tests.acceptance.dsl.node.ThreadBesuNodeRunner
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.Cluster
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.ClusterConfigurationBuilder
import org.hyperledger.besu.tests.acceptance.dsl.transaction.net.NetTransactions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testutils.FourEmptyResponsesBeaconBlocksByRangeHandler
import testutils.MisbehavingP2PNetwork
import testutils.NormalResponsesBeaconBlocksByRangeHandler
import testutils.PeeringNodeNetworkStack
import testutils.TimeOutResponsesBeaconBlocksByRangeHandler
import testutils.besu.BesuFactory
import testutils.besu.BesuTransactionsHelper
import testutils.besu.ethGetBlockByNumber
import testutils.maru.MaruFactory
import testutils.maru.awaitTillMaruHasPeers

class MaruPeerScoringTest {
  private lateinit var cluster: Cluster
  private lateinit var validatorStack: PeeringNodeNetworkStack
  private lateinit var followerStack: PeeringNodeNetworkStack
  private lateinit var transactionsHelper: BesuTransactionsHelper
  private val log = LogManager.getLogger(this.javaClass)
  private val maruFactory = MaruFactory(pragueTimestamp = 0L)
  private lateinit var fakeLineaContract: FakeLineaRollupSmartContractClient
  private lateinit var validatorEthApiClient: EthApiClient
  private lateinit var followerEthApiClient: EthApiClient

//  @BeforeEach
//  fun setUp() {
//
//  }

  @AfterEach
  fun tearDown() {
//    followerStack.maruApp.stop()
    validatorStack.maruApp.stop()
//    followerStack.maruApp.close()
    validatorStack.maruApp.close()
    cluster.close()
  }

  @Test
  fun `node get's in sync with normal BeaconBlocksByRangeHandler`() {
    val (_, _, job) =
      setUpNodes(beaconBlocksByRangeHandlerFactory = ::NormalResponsesBeaconBlocksByRangeHandler)

    await
      .atMost(20.seconds.toJavaDuration())
      .pollInterval(200.milliseconds.toJavaDuration())
      .ignoreExceptions()
      .untilAsserted {
        assertThat(
          followerEthApiClient.getBlockByNumberWithoutTransactionsData(BlockParameter.Tag.LATEST).get().number,
        ).isGreaterThanOrEqualTo(15UL)
      }

    job.cancel()
  }

  @Test
  fun `node disconnects validator when BeaconBlocksByRangeHandler sends empty reasponses`() {
    val (validatorMaruApp, followerMaruApp, job) =
      setUpNodes(
        beaconBlocksByRangeHandlerFactory = ::FourEmptyResponsesBeaconBlocksByRangeHandler,
        banPeriod = 1000.milliseconds,
        cooldownPeriod = 500.milliseconds,
      )

    // In setUpNodes we have made sure that the validator and the follower have 1 peer
    // Now wait until it is disconnected because of empty responses
    println("Before: Follower has ${followerMaruApp.p2pNetwork.getPeers().size} peers")
    await
      .atMost(2.seconds.toJavaDuration())
      .pollInterval(10.milliseconds.toJavaDuration())
      .ignoreExceptions()
      .untilAsserted {
        assertThat(followerMaruApp.p2pNetwork.getPeers().size == 0)
      }
    // reconnects after cooldown and finishes syncing
    println("After: Follower has ${followerMaruApp.p2pNetwork.getPeers().size} peers")
    await
      .atMost(20.seconds.toJavaDuration())
      .pollInterval(200.milliseconds.toJavaDuration())
      .ignoreExceptions()
      .untilAsserted {
        assertThat(
          followerEthApiClient.getBlockByNumberWithoutTransactionsData(BlockParameter.Tag.LATEST).get().number,
        ).isGreaterThanOrEqualTo(18UL)
      }

    job.cancel()
  }

  @Test
  fun `node disconnects validator when BeaconBlocksByRangeHandler takes too long to respond`() {
    val (validatorMaruApp, followerMaruApp, job) =
      setUpNodes(beaconBlocksByRangeHandlerFactory = ::TimeOutResponsesBeaconBlocksByRangeHandler)

    await
      .atMost(2.seconds.toJavaDuration())
      .pollInterval(10.milliseconds.toJavaDuration())
      .ignoreExceptions()
      .untilAsserted {
        assertThat(followerMaruApp.p2pNetwork.getPeers().size == 0)
      }

    job.cancel()
  }

  private fun setUpNodes(
    beaconBlocksByRangeHandlerFactory: (BeaconChain) -> BeaconBlocksByRangeHandler,
    banPeriod: Duration = 10.seconds,
    cooldownPeriod: Duration = 10.seconds,
  ): Triple<MaruApp, MaruApp, Job> {
    fakeLineaContract = FakeLineaRollupSmartContractClient()
    transactionsHelper = BesuTransactionsHelper()
    cluster =
      Cluster(
        ClusterConfigurationBuilder().build(),
        NetConditions(NetTransactions()),
        ThreadBesuNodeRunner(),
      )

    validatorStack = PeeringNodeNetworkStack()
    followerStack =
      PeeringNodeNetworkStack(
        besuBuilder = { BesuFactory.buildTestBesu(validator = false) },
      )

    PeeringNodeNetworkStack.startBesuNodes(cluster, validatorStack, followerStack)

    val tcpPort = findFreePort()
    val udpPort = findFreePort()
    val validatorMaruApp =
      maruFactory.buildTestMaruValidatorWithDiscovery(
        ethereumJsonRpcUrl = validatorStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = validatorStack.besuNode.engineRpcUrl().get(),
        dataDir = validatorStack.tmpDir,
        overridingLineaContractClient = fakeLineaContract,
        p2pPort = tcpPort,
        discoveryPort = udpPort,
        cooldownPeriod = cooldownPeriod,
        p2pNetworkFactory = {
          privateKeyBytes,
          p2pConfig,
          chainId,
          serDe,
          metricsFacade,
          metricsSystem,
          smf,
          chain,
          forkIdHashProvider,
          isBlockImportEnabledProvider,
          ->
          MisbehavingP2PNetwork(
            privateKeyBytes,
            p2pConfig,
            chainId,
            serDe,
            metricsFacade,
            metricsSystem,
            smf,
            chain,
            forkIdHashProvider,
            isBlockImportEnabledProvider,
            // You must provide a default or test-specific handler factory here
            beaconBlocksByRangeHandlerFactory,
          )
        },
      )

    validatorStack.setMaruApp(validatorMaruApp)
    validatorStack.maruApp.start()

    println("validator local node record: ${validatorStack.maruApp.p2pNetwork.localNodeRecord}")
    val bootnodeEnr =
      validatorStack.maruApp.p2pNetwork.localNodeRecord
        ?.asEnr()

    validatorEthApiClient =
      createEthApiClient(
        rpcUrl = validatorStack.besuNode.jsonRpcBaseUrl().get(),
        log = LogManager.getLogger("clients.l2.test.validator"),
        requestRetryConfig = null,
        vertx = null,
      )

    await
      .atMost(20.seconds.toJavaDuration())
      .pollInterval(200.milliseconds.toJavaDuration())
      .ignoreExceptions()
      .untilAsserted {
        assertThat(
          validatorEthApiClient.getBlockByNumberWithoutTransactionsData(BlockParameter.Tag.LATEST).get().number,
        ).isGreaterThanOrEqualTo(0UL)
      }

    val validatorGenesis = validatorStack.besuNode.ethGetBlockByNumber("earliest", false)
    val followerGenesis = followerStack.besuNode.ethGetBlockByNumber("earliest", false)
    assertThat(validatorGenesis).isEqualTo(followerGenesis)

    val tcpPortFollower = findFreePort()
    val udpPortFollower = findFreePort()
    val followerMaruApp =
      maruFactory.buildTestMaruFollowerWithDiscovery(
        ethereumJsonRpcUrl = followerStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = followerStack.besuNode.engineRpcUrl().get(),
        dataDir = followerStack.tmpDir,
        overridingLineaContractClient = fakeLineaContract,
        bootnode = bootnodeEnr,
        p2pPort = tcpPortFollower,
        discoveryPort = udpPortFollower,
        banPeriod = banPeriod,
        cooldownPeriod = cooldownPeriod,
        p2pNetworkFactory = {
          privateKeyBytes,
          p2pConfig,
          chainId,
          serDe,
          metricsFacade,
          metricsSystem,
          smf,
          chain,
          forkIdHashProvider,
          isBlockImportEnabledProvider,
          ->
          MisbehavingP2PNetwork(
            privateKeyBytes,
            p2pConfig,
            chainId,
            serDe,
            metricsFacade,
            metricsSystem,
            smf,
            chain,
            forkIdHashProvider,
            isBlockImportEnabledProvider,
            // You must provide a default or test-specific handler factory here
            beaconBlocksByRangeHandlerFactory,
          )
        },
      )
    followerStack.setMaruApp(followerMaruApp)

    val job =
      CoroutineScope(Dispatchers.Default).launch {
        repeat(50) {
          transactionsHelper.run {
            validatorStack.besuNode.sendTransactionAndAssertExecution(
              logger = log,
              recipient = createAccount("another account"),
              amount = Amount.ether(1),
            )
          }
        }
      }

    await
      .atMost(20.seconds.toJavaDuration())
      .pollInterval(200.milliseconds.toJavaDuration())
      .ignoreExceptions()
      .untilAsserted {
        assertThat(
          validatorEthApiClient.getBlockByNumberWithoutTransactionsData(BlockParameter.Tag.LATEST).get().number,
        ).isGreaterThanOrEqualTo(10UL)
      }

    followerStack.maruApp.start()

    validatorStack.maruApp.awaitTillMaruHasPeers(1u)
    followerStack.maruApp.awaitTillMaruHasPeers(1u)
    log.info("Follower has ${followerMaruApp.p2pNetwork.getPeers().size} peers")

    followerEthApiClient =
      createEthApiClient(
        rpcUrl = followerStack.besuNode.jsonRpcBaseUrl().get(),
        log = LogManager.getLogger("clients.l2.test.follower"),
        requestRetryConfig = null,
        vertx = null,
      )
    // wait for Besu to be fully started and synced,
    // to avoid CI flakiness due to low resources sometimes
    await
      .atMost(20.seconds.toJavaDuration())
      .pollInterval(200.milliseconds.toJavaDuration())
      .ignoreExceptions()
      .untilAsserted {
        assertThat(
          followerEthApiClient.getBlockByNumberWithoutTransactionsData(BlockParameter.Tag.LATEST).get().number,
        ).isGreaterThanOrEqualTo(0UL)
      }
    return Triple(validatorMaruApp, followerMaruApp, job)
  }

  private fun findFreePort(): UInt =
    runCatching {
      ServerSocket(0).use { socket ->
        socket.reuseAddress = true
        socket.localPort.toUInt()
      }
    }.getOrElse {
      throw IllegalStateException("Could not find a free port", it)
    }
}
