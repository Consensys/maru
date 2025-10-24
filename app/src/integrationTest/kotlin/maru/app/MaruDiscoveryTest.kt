/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import org.junit.jupiter.api.fail
import testutils.PeeringNodeNetworkStack
import testutils.TestUtils.findFreePort
import testutils.besu.BesuFactory
import testutils.besu.BesuTransactionsHelper
import testutils.maru.MaruFactory

/**
 * Test suite for Maru peer discovery with multiple nodes.
 * Tests that multiple Maru nodes can discover each other using discovery protocol.
 */
class MaruDiscoveryTest {
  private lateinit var cluster: Cluster
  private val networkStacks = mutableListOf<PeeringNodeNetworkStack>()
  private val maruApps = mutableListOf<MaruApp>()
  private lateinit var transactionsHelper: BesuTransactionsHelper
  private val log = LogManager.getLogger(this.javaClass)
  private val maruFactory = MaruFactory()
  private var job: Job? = null

  @AfterEach
  fun tearDown() {
    job?.cancel()
    maruApps.forEach { app ->
      try {
        app.stop()
        app.close()
      } catch (e: Exception) {
        log.warn("Error stopping Maru app", e)
      }
    }
    maruApps.clear()

    networkStacks.clear()

    if (::cluster.isInitialized) {
      cluster.close()
    }
  }

  @Test
  fun `ten nodes discover each other via bootnode`() {
    testMultiNodeDiscovery(numberOfNodes = 10, expectedPeers = 9u)
  }

  /**
   * Tests peer discovery with multiple Maru nodes.
   *
   * Setup:
   * - Creates N Besu nodes (EL) and N Maru nodes (CL)
   * - First Maru node is the bootnode
   * - All other nodes use the bootnode's ENR for discovery
   *
   * @param numberOfNodes Total number of Maru nodes to create
   * @param expectedPeers Number of peers each node should discover
   */
  private fun testMultiNodeDiscovery(
    numberOfNodes: Int,
    expectedPeers: UInt,
  ) {
    require(numberOfNodes >= 2) { "Need at least 2 nodes for discovery test" }

    log.info("Starting peer discovery test with $numberOfNodes nodes")

    // Initialize test infrastructure
    transactionsHelper = BesuTransactionsHelper()
    cluster =
      Cluster(
        ClusterConfigurationBuilder().build(),
        NetConditions(NetTransactions()),
        ThreadBesuNodeRunner(),
      )

    // Create and start all network stacks (Besu + Maru)
    repeat(numberOfNodes) { index ->
      val isValidator = index == 0 // First node is validator, rest are followers
      val stack =
        PeeringNodeNetworkStack(
          besuBuilder = {
            BesuFactory.buildTestBesu(validator = isValidator)
          },
        )
      networkStacks.add(stack)
    }

    // Start all Besu nodes (they will automatically peer with each other at EL layer)
    log.info("Starting ${networkStacks.size} Besu nodes")
    PeeringNodeNetworkStack.startBesuNodes(cluster, *networkStacks.toTypedArray())

    // Wait for Besu nodes to be ready
    await
      .atMost(100.seconds.toJavaDuration())
      .pollInterval(500.milliseconds.toJavaDuration())
      .untilAsserted {
        networkStacks.forEach { stack ->
          val blockNumber = stack.besuNode.execute(transactionsHelper.ethTransactions.blockNumber())
          assertThat(blockNumber).isNotNull
        }
      }

    log.info("All Besu nodes are ready")

    // Create and start the bootnode (validator)
    val bootnodeStack = networkStacks[0]
    val bootnodeTcpPort = findFreePort()
    val bootnodeUdpPort = findFreePort()

    log.info("Creating bootnode Maru on TCP port $bootnodeTcpPort, UDP port $bootnodeUdpPort")
    val bootnodeMaruApp =
      maruFactory.buildTestMaruValidatorWithDiscovery(
        ethereumJsonRpcUrl = bootnodeStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = bootnodeStack.besuNode.engineRpcUrl().get(),
        dataDir = bootnodeStack.tmpDir,
        p2pPort = bootnodeTcpPort,
        discoveryPort = bootnodeUdpPort,
        allowEmptyBlocks = true,
      )

    bootnodeStack.setMaruApp(bootnodeMaruApp)
    maruApps.add(bootnodeMaruApp)
    bootnodeMaruApp.start()

    // Get bootnode ENR for other nodes to use
    val bootnodeEnr = bootnodeMaruApp.p2pNetwork.localNodeRecord?.asEnr()
    requireNotNull(bootnodeEnr) { "Bootnode ENR should not be null" }
    log.info("Bootnode ENR: $bootnodeEnr")

    // Start block production on validator
    log.info("Starting block production on validator")

    val handler =
      CoroutineExceptionHandler { _, exception ->
        fail("Coroutine failed with exception: $exception")
      }

    job =
      CoroutineScope(Dispatchers.Default).launch(handler) {
        while (true) {
          transactionsHelper.run {
            bootnodeStack.besuNode.sendTransactionAndAssertExecution(
              logger = log,
              recipient = createAccount("another account"),
              amount = Amount.ether(1),
            )
          }
        }
      }

    try {
      // Wait for some blocks to be produced
      await
        .atMost(20.seconds.toJavaDuration())
        .pollInterval(500.milliseconds.toJavaDuration())
        .untilAsserted {
          val blockNumber = bootnodeStack.besuNode.execute(transactionsHelper.ethTransactions.blockNumber())
          assertThat(blockNumber.toLong()).isGreaterThanOrEqualTo(5L)
        }

      log.info("Validator is producing blocks")

      // Create and start follower nodes
      for (i in 1 until numberOfNodes) {
        val stack = networkStacks[i]
        val tcpPort = findFreePort()
        val udpPort = findFreePort()

        log.info("Creating follower node $i on TCP port $tcpPort, UDP port $udpPort")
        val followerMaruApp =
          maruFactory.buildTestMaruFollowerWithDiscovery(
            ethereumJsonRpcUrl = stack.besuNode.jsonRpcBaseUrl().get(),
            engineApiRpc = stack.besuNode.engineRpcUrl().get(),
            dataDir = stack.tmpDir,
            bootnode = bootnodeEnr,
            p2pPort = tcpPort,
            discoveryPort = udpPort,
            allowEmptyBlocks = true,
          )

        stack.setMaruApp(followerMaruApp)
        maruApps.add(followerMaruApp)
        followerMaruApp.start()
      }

      log.info("All $numberOfNodes Maru nodes started")

      // Wait for all nodes to discover each other
      log.info("Waiting for all nodes to discover $expectedPeers peers")
      await
        .atMost(60.seconds.toJavaDuration())
        .pollInterval(2.seconds.toJavaDuration())
        .untilAsserted {
          maruApps.forEachIndexed { index, app ->
            val peerCount = app.peersConnected()
            log.info("Node $index has $peerCount peers (expected: $expectedPeers)")
            assertThat(peerCount).isGreaterThanOrEqualTo(expectedPeers)
          }
        }

      log.info("All nodes have discovered their peers!")

      // Verify each node can see the others
      maruApps.forEachIndexed { index, app ->
        val peers = app.p2pNetwork().getPeers()
        log.info("Node $index peers: ${peers.map { it.nodeId }}")
        assertThat(peers.size).isGreaterThanOrEqualTo(expectedPeers.toInt())
      }

      log.info("Verifying followers sync EL locks")
      val validatorBlockHeight =
        networkStacks[0].besuNode.execute(transactionsHelper.ethTransactions.blockNumber()).toLong()

      await
        .atMost(30.seconds.toJavaDuration())
        .pollInterval(1.seconds.toJavaDuration())
        .untilAsserted {
          networkStacks.forEachIndexed { i, stack ->
            val followerBlockHeight =
              stack.besuNode.execute(transactionsHelper.ethTransactions.blockNumber()).toLong()
            log.info("Follower $i EL block height: $followerBlockHeight (validator: $validatorBlockHeight)")
            assertThat(followerBlockHeight).isGreaterThanOrEqualTo(validatorBlockHeight - 2)
          }
        }

      log.info("All followers have synced EL blocks successfully!")
    } catch (e: Exception) {
      job?.cancel()
      throw e
    }
  }
}
