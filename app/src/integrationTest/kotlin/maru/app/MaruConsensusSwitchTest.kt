/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.io.File
import java.math.BigInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import linea.kotlin.decodeHex
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
import org.junit.jupiter.api.io.TempDir
import testutils.Checks.checkAllNodesHaveSameBlocks
import testutils.Checks.getBlockNumber
import testutils.Checks.getMinedBlocks
import testutils.Checks.verifyBlockTime
import testutils.besu.BesuFactory
import testutils.besu.BesuTransactionsHelper
import testutils.besu.ethGetBlockByNumber
import testutils.besu.startWithRetry
import testutils.maru.MaruFactory
import testutils.maru.awaitTillMaruHasPeers

class MaruConsensusSwitchTest {
  companion object {
    private const val VANILLA_EXTRA_DATA_LENGTH = 32
    private const val SECONDS_FROM_CANCUN_TO_PRAGUE_FORK = 80
  }

  private lateinit var cluster: Cluster
  private lateinit var validatorBesuNode: BesuNode
  private lateinit var validatorMaruNode: MaruApp
  private lateinit var followerBesuNode: BesuNode
  private lateinit var followerMaruNode: MaruApp
  private lateinit var transactionsHelper: BesuTransactionsHelper
  private val log = LogManager.getLogger(this.javaClass)

  @TempDir
  private lateinit var validatorMaruTmpDir: File

  @TempDir
  private lateinit var followerMaruTmpDir: File
  private val net = NetConditions(NetTransactions())

  @BeforeEach
  fun setUp() {
    transactionsHelper = BesuTransactionsHelper()
    // We'll set the switchTimestamp in the test method
    cluster =
      Cluster(
        ClusterConfigurationBuilder().build(),
        net,
        ThreadBesuNodeRunner(),
      )
  }

  @AfterEach
  fun tearDown() {
    followerMaruNode.stop().get()
    validatorMaruNode.stop().get()
    followerMaruNode.close()
    validatorMaruNode.close()
    runCatching { cluster.close() }
      .onFailure {
        log.warn(
          "Besu acceptance Cluster teardown failed (ignored so the test outcome reflects assertions only)",
          it,
        )
      }
  }

  private fun verifyConsensusSwitch(
    besuNode: BesuNode,
    expectedBlocksBeforeMerge: Int,
    totalBlocksToProduce: Int,
  ) {
    val blockProducedByQbft = besuNode.ethGetBlockByNumber(1UL)
    assertThat(blockProducedByQbft.extraData.decodeHex().size).isGreaterThan(VANILLA_EXTRA_DATA_LENGTH)

    val blocks = besuNode.getMinedBlocks(totalBlocksToProduce)
    assertThat(blocks.size).isGreaterThanOrEqualTo(expectedBlocksBeforeMerge + 1)
    val parisSwitchBlockIndex = expectedBlocksBeforeMerge
    val qbftBlocks = blocks.subList(0, parisSwitchBlockIndex)
    qbftBlocks.verifyBlockTime()
    assertThat(qbftBlocks).hasSize(expectedBlocksBeforeMerge)
    blocks.subList(parisSwitchBlockIndex, blocks.size).verifyBlockTime()
  }

  @Test
  fun `follower node correctly switches from QBFT to POS after peering with Sequencer validator`() {
    val stackStartupMargin = 40UL
    val expectedBlocksBeforeMerge = 5
    val plannedTxMiningBlocks = stackStartupMargin.toInt() + expectedBlocksBeforeMerge + 20
    var currentTimestamp = (System.currentTimeMillis() / 1000).toULong()
    val shanghaiTimestamp = currentTimestamp + stackStartupMargin + expectedBlocksBeforeMerge.toULong()
    val cancunTimestamp = shanghaiTimestamp + 10u
    val pragueTimestamp = cancunTimestamp + 10u + SECONDS_FROM_CANCUN_TO_PRAGUE_FORK.toULong()
    val totalBlocksToProduce = plannedTxMiningBlocks
    val ttd = expectedBlocksBeforeMerge.toULong() * 2UL
    log.info(
      "Setting Prague switch timestamp to $pragueTimestamp, shanghai switch to $shanghaiTimestamp, Cancun switch to " +
        "$cancunTimestamp, current timestamp: $currentTimestamp",
    )

    validatorBesuNode =
      BesuFactory.buildSwitchableBesuQbft(
        shanghaiTimestamp = shanghaiTimestamp,
        cancunTimestamp = cancunTimestamp,
        pragueTimestamp = pragueTimestamp,
        ttd = ttd,
        validator = true,
      )
    followerBesuNode =
      BesuFactory.buildSwitchableBesuQbft(
        shanghaiTimestamp = shanghaiTimestamp,
        cancunTimestamp = cancunTimestamp,
        pragueTimestamp = pragueTimestamp,
        ttd = ttd,
        validator = false,
      )
    cluster.startWithRetry(validatorBesuNode)

    await
      .atMost(180.seconds.toJavaDuration())
      .pollDelay(1.seconds.toJavaDuration())
      .untilAsserted {
        assertThat(validatorBesuNode.getBlockNumber()).isGreaterThanOrEqualTo(BigInteger.ONE)
      }

    transactionsHelper.run {
      validatorBesuNode.sendTransactionAndAssertExecution(
        logger = log,
        recipient = createAccount("pre-maru smoke"),
        amount = Amount.ether(1),
      )
    }

    cluster.addNode(followerBesuNode)

    val validatorEthereumJsonRpcBaseUrl = validatorBesuNode.jsonRpcBaseUrl().get()
    val validatorEngineRpcUrl = validatorBesuNode.engineRpcUrl().get()

    val maruFactory =
      MaruFactory(
        pragueTimestamp = pragueTimestamp,
        cancunTimestamp = cancunTimestamp,
        shanghaiTimestamp = shanghaiTimestamp,
        ttd = ttd,
      )
    validatorMaruNode =
      maruFactory.buildSwitchableTestMaruValidatorWithP2pPeering(
        ethereumJsonRpcUrl = validatorEthereumJsonRpcBaseUrl,
        engineApiRpc = validatorEngineRpcUrl,
        dataDir = validatorMaruTmpDir.toPath(),
      )
    validatorMaruNode.start().get()

    val followerEthereumJsonRpcBaseUrl = followerBesuNode.jsonRpcBaseUrl().get()
    val followerEngineRpcUrl = followerBesuNode.engineRpcUrl().get()

    followerMaruNode =
      maruFactory.buildTestMaruFollowerWithConsensusSwitch(
        ethereumJsonRpcUrl = followerEthereumJsonRpcBaseUrl,
        engineApiRpc = followerEngineRpcUrl,
        dataDir = followerMaruTmpDir.toPath(),
        validatorPortForStaticPeering = validatorMaruNode.p2pPort(),
      )
    followerMaruNode.start().get()

    followerMaruNode.awaitTillMaruHasPeers(1u)
    validatorMaruNode.awaitTillMaruHasPeers(1u)

    log.info("Sending transactions")
    repeat(totalBlocksToProduce) {
      transactionsHelper.run {
        validatorBesuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("pre-switch account"),
          amount = Amount.ether(100),
        )
      }
    }

    currentTimestamp = (System.currentTimeMillis() / 1000).toULong()
    log.info("Current timestamp: $currentTimestamp, prague switch timestamp: $pragueTimestamp")
    assertThat(currentTimestamp).isGreaterThan(cancunTimestamp)

    // Wait for both nodes to have all blocks before verifying contents.
    // The follower may still be syncing when the validator has already committed all blocks.
    checkAllNodesHaveSameBlocks(totalBlocksToProduce, validatorBesuNode, followerBesuNode, timeout = 60.seconds)

    verifyConsensusSwitch(
      besuNode = validatorBesuNode,
      expectedBlocksBeforeMerge = expectedBlocksBeforeMerge,
      totalBlocksToProduce = totalBlocksToProduce,
    )
    verifyConsensusSwitch(
      besuNode = followerBesuNode,
      expectedBlocksBeforeMerge = expectedBlocksBeforeMerge,
      totalBlocksToProduce = totalBlocksToProduce,
    )
  }
}
