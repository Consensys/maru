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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import maru.config.QbftConfig
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.Core
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.config.plugins.Plugin
import org.apache.logging.log4j.core.config.plugins.PluginAttribute
import org.apache.logging.log4j.core.config.plugins.PluginFactory
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
import testutils.SingleNodeNetworkStack
import testutils.besu.BesuTransactionsHelper
import testutils.maru.MaruFactory

@Plugin(name = "ListAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE, printObject = true)
class ListAppender(
  name: String,
  filter: Filter?,
) : AbstractAppender(name, filter, null, true, Property.EMPTY_ARRAY) {
  val events: MutableList<LogEvent> = CopyOnWriteArrayList()

  override fun append(event: LogEvent) {
    events.add(event.toImmutable())
  }

  fun clear() {
    events.clear()
  }

  companion object {
    @JvmStatic
    @PluginFactory
    fun createAppender(
      @PluginAttribute("name") name: String,
    ): ListAppender = ListAppender(name, null)
  }
}

class MaruLongRunningTransactionTest {
  private lateinit var cluster: Cluster
  private lateinit var networkParticipantStack: SingleNodeNetworkStack
  private lateinit var transactionsHelper: BesuTransactionsHelper
  private val log = LogManager.getLogger(this.javaClass)
  private val maruFactory = MaruFactory()
  private lateinit var listAppender: ListAppender

  private val expectedMinBuildTime = 1700L

  @BeforeEach
  fun setUp() {
    // Setup Log4j2 Appender
    listAppender = ListAppender.createAppender("ListAppender-${UUID.randomUUID()}")
    listAppender.start()

    val fcuLogger =
      LogManager.getLogger(
        "org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.AbstractEngineForkchoiceUpdated",
      ) as Logger
    fcuLogger.addAppender(listAppender)
    fcuLogger.level = Level.INFO

    val getPayloadLogger =
      LogManager.getLogger(
        "org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.AbstractEngineGetPayload",
      ) as Logger
    getPayloadLogger.addAppender(listAppender)
    getPayloadLogger.level = Level.INFO

    transactionsHelper = BesuTransactionsHelper()
    cluster =
      Cluster(
        ClusterConfigurationBuilder().build(),
        NetConditions(NetTransactions()),
        ThreadBesuNodeRunner(),
      )

    networkParticipantStack =
      SingleNodeNetworkStack(cluster = cluster) { ethereumJsonRpcBaseUrl, engineRpcUrl, tmpDir ->
        maruFactory.buildTestMaruValidatorWithoutP2pPeering(
          ethereumJsonRpcUrl = ethereumJsonRpcBaseUrl,
          engineApiRpc = engineRpcUrl,
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
    cluster.close()

    val fcuLogger =
      LogManager.getLogger(
        "org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.AbstractEngineForkchoiceUpdated",
      ) as Logger
    fcuLogger.removeAppender(listAppender)

    val getPayloadLogger =
      LogManager.getLogger(
        "org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.AbstractEngineGetPayload",
      ) as Logger
    getPayloadLogger.removeAppender(listAppender)

    listAppender.stop()
  }

  @Test
  fun `Maru waits for minBlockBuildTime in Round 1 when empty blocks are rejected`() {
    mineBlockWithTransaction(expectedBlockNumber = 1)

    // Clear logs to only capture Block 2 building
    listAppender.clear()

    // In Round 0, it will create an empty block (since we send no transactions) and it will be rejected.
    // Then it will transition to Round 1 and use EagerQbftBlockCreator, which sleeps for minBlockBuildTime.
    waitForBlockBuildingGapToExceed(expectedMinBuildTime - 50L)

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

  private fun waitForBlockBuildingGapToExceed(minimumGapMs: Long) {
    var maxGap = 0L
    await.atMost(Duration.ofSeconds(30)).untilAsserted {
      val fcuLogs = listAppender.events.filter { it.message.formattedMessage.contains("FCU(VALID)") }
      val getPayloadLogs = listAppender.events.filter { it.message.formattedMessage.contains("Produced #") }

      val hasLongRunningBlock =
        getPayloadLogs.any { getPayloadLog ->
          val precedingFcu = fcuLogs.lastOrNull { it.timeMillis <= getPayloadLog.timeMillis }
          if (precedingFcu != null) {
            val gap = getPayloadLog.timeMillis - precedingFcu.timeMillis
            if (gap > maxGap) {
              maxGap = gap
            }
            gap >= minimumGapMs
          } else {
            false
          }
        }

      assertThat(hasLongRunningBlock)
        .withFailMessage {
          val fcuDump = fcuLogs.joinToString("\n") { "FCU at ${it.timeMillis}: ${it.message.formattedMessage}" }
          val payloadDump =
            getPayloadLogs.joinToString("\n") { "Payload at ${it.timeMillis}: ${it.message.formattedMessage}" }
          val allLogsDump =
            listAppender.events.joinToString("\n") {
              "[${it.threadName}] ${it.timeMillis}: ${it.message.formattedMessage}"
            }

          """
          No block building process took >= $minimumGapMs ms. Max gap found so far: $maxGap ms

          FCU Logs:
          $fcuDump

          Payload Logs:
          $payloadDump

          All Logs:
          $allLogsDump
          """.trimIndent()
        }.isTrue()
    }
  }
}
