/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import maru.config.QbftConfig
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testutils.Checks.getMinedBlocks
import testutils.RecordingEngineProxy
import testutils.SingleNodeNetworkStack
import testutils.besu.BesuTransactionsHelper
import testutils.maru.MaruFactory

class MaruLongRunningTransactionTest {
  private lateinit var cluster: Cluster
  private lateinit var networkParticipantStack: SingleNodeNetworkStack
  private lateinit var transactionsHelper: BesuTransactionsHelper
  private val log = LogManager.getLogger(this.javaClass)
  private val maruFactory = MaruFactory()
  private lateinit var proxy: RecordingEngineProxy

  private val expectedMinBuildTime = 1700L

  @BeforeEach
  fun setUp() {
    transactionsHelper = BesuTransactionsHelper()
    cluster =
      Cluster(
        ClusterConfigurationBuilder().build(),
        NetConditions(NetTransactions()),
        ThreadBesuNodeRunner(),
      )

    networkParticipantStack =
      SingleNodeNetworkStack(cluster = cluster) { ethereumJsonRpcBaseUrl, engineRpcUrl, tmpDir ->
        proxy = RecordingEngineProxy(engineRpcUrl)
        proxy.start()

        maruFactory.buildTestMaruValidatorWithoutP2pPeering(
          ethereumJsonRpcUrl = ethereumJsonRpcBaseUrl,
          engineApiRpc = proxy.url(),
          dataDir = tmpDir,
          allowEmptyBlocks = false,
          syncingConfig =
            MaruFactory.defaultSyncingConfig.copy(
              elSyncStatusRefreshInterval = 1000.seconds,
            ),
          qbftOptions =
            QbftConfig(
              feeRecipient = maruFactory.qbftValidator.address.reversedArray(),
              minBlockBuildTime = expectedMinBuildTime.milliseconds,
              roundExpiry = 2.seconds,
            ),
        )
      }
    networkParticipantStack.maruApp.start().get()
  }

  @AfterEach
  fun tearDown() {
    networkParticipantStack.maruApp.stop().get()
    networkParticipantStack.maruApp.close()
    proxy.stop()
    cluster.close()
  }

  @Test
  fun `Maru waits for minBlockBuildTime in Round 1 when empty blocks are rejected`() {
    mineBlockWithTransaction(expectedBlockNumber = 1)

    proxy.clear()

    // In Round 0, Maru creates an empty block (no pending transactions) which gets rejected.
    // It transitions to Round 1 and uses EagerQbftBlockCreator, which sends FCU then sleeps for minBlockBuildTime.
    waitForFcuToGetPayloadGapToExceed(expectedMinBuildTime - 100L)

    mineBlockWithTransaction(expectedBlockNumber = 2)
  }

  private fun mineBlockWithTransaction(expectedBlockNumber: Int) {
    transactionsHelper.run {
      networkParticipantStack.besuNode.sendTransactionAndAssertExecution(
        logger = log,
        recipient = createAccount("another account"),
        amount = Amount.ether(100),
      )
    }
    assertThat(networkParticipantStack.besuNode.getMinedBlocks(expectedBlockNumber)).hasSize(expectedBlockNumber)
  }

  private fun waitForFcuToGetPayloadGapToExceed(minimumGapMs: Long) {
    var maxGap = 0L
    await.atMost(Duration.ofSeconds(30)).untilAsserted {
      val fcuCalls = proxy.calls.filter { it.method.startsWith("engine_forkchoiceUpdated") }
      val getPayloadCalls = proxy.calls.filter { it.method.startsWith("engine_getPayload") }

      val hasLongGap =
        getPayloadCalls.any { getPayload ->
          val precedingFcu = fcuCalls.lastOrNull { it.timestampMs <= getPayload.timestampMs }
          if (precedingFcu != null) {
            val gap = getPayload.timestampMs - precedingFcu.timestampMs
            if (gap > maxGap) maxGap = gap
            gap >= minimumGapMs
          } else {
            false
          }
        }

      assertThat(hasLongGap)
        .withFailMessage {
          val callsDump = proxy.calls.joinToString("\n") { "${it.timestampMs}: ${it.method}" }

          """
          No FCU-to-getPayload gap took >= $minimumGapMs ms. Max gap: $maxGap ms
          
          All Engine API calls:
          $callsDump
          """.trimIndent()
        }.isTrue()
    }
  }
}
