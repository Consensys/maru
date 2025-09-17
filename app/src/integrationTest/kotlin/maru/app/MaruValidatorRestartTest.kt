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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import org.apache.logging.log4j.LogManager
import org.awaitility.kotlin.await
import org.hyperledger.besu.tests.acceptance.dsl.blockchain.Amount
import org.hyperledger.besu.tests.acceptance.dsl.condition.net.NetConditions
import org.hyperledger.besu.tests.acceptance.dsl.node.ThreadBesuNodeRunner
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.Cluster
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.ClusterConfigurationBuilder
import org.hyperledger.besu.tests.acceptance.dsl.transaction.net.NetTransactions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testutils.Checks.assertMinedBlocks
import testutils.PeeringNodeNetworkStack
import testutils.besu.BesuFactory
import testutils.besu.BesuTransactionsHelper
import testutils.maru.MaruFactory
import testutils.maru.awaitTillMaruHasPeers

class MaruValidatorRestartTest {
  private lateinit var cluster: Cluster
  private lateinit var transactionsHelper: BesuTransactionsHelper
  private val maruFactory = MaruFactory()
  private val log = LogManager.getLogger(this.javaClass)

  @BeforeEach
  fun setup() {
    cluster =
      Cluster(
        ClusterConfigurationBuilder().build(),
        NetConditions(NetTransactions()),
        ThreadBesuNodeRunner(),
      )
    transactionsHelper = BesuTransactionsHelper()
  }

  @AfterEach
  fun tearDown() {
    cluster.close()
  }

  @Test
  fun `Maru validator restarted from scratch is able to sync state`() {
    val validatorStack = PeeringNodeNetworkStack()
    val followerStack =
      PeeringNodeNetworkStack(
        besuBuilder = { BesuFactory.buildTestBesu(validator = false) },
      )
    PeeringNodeNetworkStack.startBesuNodes(cluster, validatorStack, followerStack)

    val followerMaruApp =
      maruFactory.buildTestMaruFollowerWithDiscovery(
        ethereumJsonRpcUrl = followerStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = followerStack.besuNode.engineRpcUrl().get(),
        dataDir = followerStack.tmpDir,
        p2pPort = findFreePort(),
        discoveryPort = findFreePort(),
      )
    followerStack.setMaruApp(followerMaruApp)
    followerStack.maruApp.start()

    val validatorMaruApp =
      maruFactory.buildTestMaruValidatorWithDiscovery(
        ethereumJsonRpcUrl = validatorStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = validatorStack.besuNode.engineRpcUrl().get(),
        dataDir = validatorStack.tmpDir,
        p2pPort = findFreePort(),
        discoveryPort = findFreePort(),
        bootnode =
          followerStack.maruApp.p2pNetwork.localNodeRecord
            ?.asEnr(),
      )
    validatorStack.setMaruApp(validatorMaruApp)
    validatorStack.maruApp.start()

    followerStack.maruApp.awaitTillMaruHasPeers(1u)
    validatorStack.maruApp.awaitTillMaruHasPeers(1u)

    val blocksToProduce = 5
    repeat(blocksToProduce) {
      transactionsHelper.run {
        validatorStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    validatorStack.besuNode.assertMinedBlocks(blocksToProduce)
    followerStack.besuNode.assertMinedBlocks(blocksToProduce)

    validatorStack.maruApp.stop()
    validatorStack.maruApp.close()

    await
      .timeout(60.seconds.toJavaDuration())
      .pollInterval(1.seconds.toJavaDuration())
      .until { followerStack.maruApp.peersConnected() == 0u }

    val newValidatorMaruApp =
      maruFactory.buildTestMaruValidatorWithDiscovery(
        ethereumJsonRpcUrl = validatorStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = validatorStack.besuNode.engineRpcUrl().get(),
        dataDir = validatorStack.tmpDir,
        p2pPort = findFreePort(),
        discoveryPort = findFreePort(),
        bootnode =
          followerStack.maruApp.p2pNetwork.localNodeRecord
            ?.asEnr(),
      )
    validatorStack.setMaruApp(newValidatorMaruApp)
    validatorStack.maruApp.start()

    validatorStack.maruApp.awaitTillMaruHasPeers(1u, timeout = 60.seconds)
    followerStack.maruApp.awaitTillMaruHasPeers(1u, timeout = 60.seconds)

    repeat(blocksToProduce) {
      transactionsHelper.run {
        validatorStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    validatorStack.besuNode.assertMinedBlocks(2 * blocksToProduce)
    followerStack.besuNode.assertMinedBlocks(2 * blocksToProduce)

    followerStack.maruApp.stop()
    followerStack.maruApp.close()
    validatorStack.maruApp.stop()
    validatorStack.maruApp.close()
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
