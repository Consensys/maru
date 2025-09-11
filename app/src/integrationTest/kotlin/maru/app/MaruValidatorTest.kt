/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import maru.config.SyncingConfig
import org.apache.logging.log4j.LogManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.hyperledger.besu.tests.acceptance.dsl.blockchain.Amount
import org.hyperledger.besu.tests.acceptance.dsl.condition.net.NetConditions
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode
import org.hyperledger.besu.tests.acceptance.dsl.node.ThreadBesuNodeRunner
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.Cluster
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.ClusterConfigurationBuilder
import org.hyperledger.besu.tests.acceptance.dsl.transaction.net.NetTransactions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testutils.Checks.getMinedBlocks
import testutils.PeeringNodeNetworkStack
import testutils.besu.BesuFactory
import testutils.besu.BesuTransactionsHelper
import testutils.besu.ethGetBlockByNumber
import testutils.maru.MaruFactory
import testutils.maru.awaitTillMaruHasPeers

class MaruValidatorTest {
  private lateinit var cluster: Cluster
  private lateinit var validatorStack: PeeringNodeNetworkStack
  private lateinit var followerStack: PeeringNodeNetworkStack
  private lateinit var transactionsHelper: BesuTransactionsHelper
  private val log = LogManager.getLogger(this.javaClass)
  private val maruFactory = MaruFactory()

  private fun setupMaruHelper(syncingConfig: SyncingConfig = MaruFactory.defaultSyncingConfig) {
    // Create and start validator Maru app first
    val validatorMaruApp =
      maruFactory.buildTestMaruValidatorWithP2pPeering(
        ethereumJsonRpcUrl = validatorStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = validatorStack.besuNode.engineRpcUrl().get(),
        dataDir = validatorStack.tmpDir,
        syncingConfig = syncingConfig,
      )
    validatorStack.setMaruApp(validatorMaruApp)
    validatorStack.maruApp.start()

    // Get the validator's p2p port after it's started
    val validatorP2pPort = validatorStack.p2pPort

    // Create follower Maru app with the validator's p2p port for static peering
    val followerMaruApp =
      maruFactory.buildTestMaruFollowerWithP2pPeering(
        ethereumJsonRpcUrl = followerStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = followerStack.besuNode.engineRpcUrl().get(),
        dataDir = followerStack.tmpDir,
        validatorPortForStaticPeering = validatorP2pPort,
        syncingConfig = syncingConfig,
      )
    followerStack.setMaruApp(followerMaruApp)
    followerStack.maruApp.start()

    log.info("Nodes are peered")
    followerStack.maruApp.awaitTillMaruHasPeers(1u)
    validatorStack.maruApp.awaitTillMaruHasPeers(1u)
    val validatorGenesis = validatorStack.besuNode.ethGetBlockByNumber("earliest", false)
    val followerGenesis = followerStack.besuNode.ethGetBlockByNumber("earliest", false)

    assertThat(validatorGenesis).isEqualTo(followerGenesis)
  }

  @BeforeEach
  fun setUp() {
    transactionsHelper = BesuTransactionsHelper()
    cluster =
      Cluster(
        ClusterConfigurationBuilder().awaitPeerDiscovery(false).build(),
        NetConditions(NetTransactions()),
        ThreadBesuNodeRunner(),
      )

    validatorStack = PeeringNodeNetworkStack()

    followerStack =
      PeeringNodeNetworkStack(
        besuBuilder = { BesuFactory.buildTestBesu(validator = false) },
      )

    // FollowerStack's besu node should be the first to start to ensure that it becomes the cluster's Bootnode
    // This way in the test where ValidatorStack's besu node is restarted, it can still peer within the cluster.
    PeeringNodeNetworkStack.startBesuNodes(cluster, followerStack, validatorStack)
  }

  @AfterEach
  fun tearDown() {
    followerStack.maruApp.stop()
    validatorStack.maruApp.stop()
    followerStack.maruApp.close()
    validatorStack.maruApp.close()
    cluster.close()
  }

  @Test
  fun `Besu sequencer restarted from scratch is able to sync state`() {
    setupMaruHelper()
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

    checkBesuNodeBlocks(validatorStack.besuNode, blocksToProduce)
    checkBesuNodeBlocks(followerStack.besuNode, blocksToProduce)

    val oldBesuSequencer = validatorStack.besuNode
    val engineRpcPort = oldBesuSequencer.engineJsonRpcPort
    val jsonRpcPort = oldBesuSequencer.jsonRpcPort
    println("Old sequencer jsonRpcPort=$jsonRpcPort, engineRpcPort=$engineRpcPort")
    cluster.stopNode(oldBesuSequencer)
    oldBesuSequencer.stop()
    oldBesuSequencer.close()

    val newBesuSequencer =
      BesuFactory.buildTestBesu(
        jsonRpcPort = jsonRpcPort,
        engineRpcPort = engineRpcPort,
      )

    cluster.addNode(newBesuSequencer)

    await
      .pollDelay(1.seconds.toJavaDuration())
      .timeout(30.seconds.toJavaDuration())
      .untilAsserted {
        val newSequencerBlocks = newBesuSequencer.getMinedBlocks(blocksToProduce)
        assertThat(newSequencerBlocks).hasSize(blocksToProduce)
      }

    repeat(blocksToProduce) {
      transactionsHelper.run {
        newBesuSequencer.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    checkBesuNodeBlocks(newBesuSequencer, blocksToProduce * 2)
    checkBesuNodeBlocks(followerStack.besuNode, blocksToProduce * 2)
  }

  private fun checkBesuNodeBlocks(
    besuNode: BesuNode,
    expectedBlocks: Int,
  ) {
    await
      .pollDelay(1.seconds.toJavaDuration())
      .timeout(30.seconds.toJavaDuration())
      .untilAsserted {
        val minedBlocks = besuNode.getMinedBlocks(expectedBlocks)
        assertThat(minedBlocks).hasSize(expectedBlocks)
      }
  }
}
