/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import maru.config.QbftConfig
import maru.consensus.qbft.EagerQbftBlockCreator
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.Core
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.config.plugins.Plugin
import org.apache.logging.log4j.core.config.plugins.PluginAttribute
import org.apache.logging.log4j.core.config.plugins.PluginElement
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
  private val maruFactory = testutils.maru.MaruFactory()
  private lateinit var listAppender: ListAppender

  @BeforeEach
  fun setUp() {
    // Setup Log4j2 Appender
    listAppender = ListAppender.createAppender("ListAppender")
    listAppender.start()
    val loggerContext = LogManager.getContext(false) as LoggerContext
    val configuration = loggerContext.configuration
    val fcuLoggerConfig =
      configuration.getLoggerConfig(
        "org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.AbstractEngineForkchoiceUpdated",
      )
    fcuLoggerConfig.addAppender(listAppender, Level.INFO, null)
    fcuLoggerConfig.level = Level.INFO

    val getPayloadLoggerConfig =
      configuration.getLoggerConfig(
        "org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.AbstractEngineGetPayload",
      )
    getPayloadLoggerConfig.addAppender(listAppender, Level.INFO, null)
    getPayloadLoggerConfig.level = Level.INFO

    loggerContext.updateLoggers()

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
          qbftOptions =
            QbftConfig(
              feeRecipient = maruFactory.qbftValidator.address.reversedArray(),
              minBlockBuildTime = 1700.milliseconds,
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

    val loggerContext = LogManager.getContext(false) as LoggerContext
    val configuration = loggerContext.configuration
    val fcuLoggerConfig =
      configuration.getLoggerConfig(
        "org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.AbstractEngineForkchoiceUpdated",
      )
    fcuLoggerConfig.removeAppender(listAppender.name)
    val getPayloadLoggerConfig =
      configuration.getLoggerConfig(
        "org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.AbstractEngineGetPayload",
      )
    getPayloadLoggerConfig.removeAppender(listAppender.name)
    loggerContext.updateLoggers()
    listAppender.stop()
  }

  @Test
  fun `Maru waits for minBlockBuildTime in Round 1 when empty blocks are rejected`() {
    // 1. Mine Block 1 successfully to transition out of the initial state
    transactionsHelper.run {
      networkParticipantStack.besuNode.sendTransactionAndAssertExecution(
        logger = log,
        recipient = createAccount("another account"),
        amount = Amount.ether(100),
      )
    }

    val blocks = networkParticipantStack.besuNode.getMinedBlocks(1)
    assertThat(blocks).hasSize(1)

    // 2. Clear logs to only capture Block 2 building
    listAppender.clear()

    // 3. Wait for Maru to attempt to build Block 2.
    // In Round 0, it will create an empty block (since we send no transactions) and it will be rejected (allowEmptyBlocks = false).
    // Then it will transition to Round 1 and use EagerQbftBlockCreator.
    await.untilAsserted {
      val fcuLogs =
        listAppender.events.filter { it.message.formattedMessage.contains("FCU(VALID)") }
      val getPayloadLogs =
        listAppender.events.filter {
          it.message.formattedMessage.contains("Produced #")
        }

      assertThat(fcuLogs).isNotEmpty()
      assertThat(getPayloadLogs).isNotEmpty()

      // We might have multiple FCU logs (e.g. for Round 0 and Round 1).
      // The last FCU log corresponds to Round 1.
      val lastFcuTime = fcuLogs.last().timeMillis
      val lastGetPayloadTime = getPayloadLogs.last().timeMillis

      val timeDiff = lastGetPayloadTime - lastFcuTime
      assertThat(timeDiff).isGreaterThanOrEqualTo(1700L)
    }

    // 4. Send a transaction so Block 2 is not empty and gets successfully mined
    transactionsHelper.run {
      networkParticipantStack.besuNode.sendTransactionAndAssertExecution(
        logger = log,
        recipient = createAccount("another account"),
        amount = Amount.ether(100),
      )
    }

    val blocksAfterRound1 = networkParticipantStack.besuNode.getMinedBlocks(2)
    assertThat(blocksAfterRound1).hasSize(2)
  }
}
