/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.math.BigInteger
import kotlin.collections.map
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import maru.config.SyncingConfig
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.web3j.protocol.core.methods.response.EthBlock
import testutils.Checks.getBlockNumber
import testutils.Checks.getMinedBlocks
import testutils.PeeringNodeNetworkStack
import testutils.besu.BesuFactory
import testutils.besu.BesuTransactionsHelper
import testutils.besu.ethGetBlockByNumber
import testutils.besu.startWithRetry
import testutils.maru.MaruFactory
import testutils.maru.awaitTillMaruHasPeers

class MaruFollowerTest {
  companion object {
    @JvmStatic
    fun enumeratingSyncingConfigs(): List<SyncingConfig> = MaruFactory.enumeratingSyncingConfigs()
  }

  private lateinit var cluster: Cluster
  private lateinit var validatorStack: PeeringNodeNetworkStack
  private lateinit var followerStack: PeeringNodeNetworkStack
  private lateinit var transactionsHelper: BesuTransactionsHelper
  private val log = LogManager.getLogger(this.javaClass)
  private val maruFactory = MaruFactory()
  private val desyncTolerance = 0UL

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
        ClusterConfigurationBuilder().build(),
        NetConditions(NetTransactions()),
        ThreadBesuNodeRunner(),
      )

    validatorStack = PeeringNodeNetworkStack()

    followerStack =
      PeeringNodeNetworkStack(
        besuBuilder = { BesuFactory.buildTestBesu(validator = false) },
      )

    // Start all Besu nodes together for proper peering
    PeeringNodeNetworkStack.startBesuNodes(cluster, validatorStack, followerStack)
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
  fun `Maru follower is able to import blocks`() {
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

    checkValidatorAndFollowerBlocks(blocksToProduce)
  }

  @Test
  fun `Maru follower is able to import blocks after going down`() {
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

    // This is here mainly to wait until block propagation is complete
    checkValidatorAndFollowerBlocks(blocksToProduce)

    followerStack.maruApp.stop()
    followerStack.maruApp.close()
    followerStack.setMaruApp(
      maruFactory.buildTestMaruFollowerWithP2pPeering(
        ethereumJsonRpcUrl = followerStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = followerStack.besuNode.engineRpcUrl().get(),
        dataDir = followerStack.tmpDir,
        validatorPortForStaticPeering = validatorStack.p2pPort,
        syncingConfig = MaruFactory.defaultSyncingConfig.copy(desyncTolerance = desyncTolerance),
      ),
    )
    followerStack.maruApp.start()

    followerStack.maruApp.awaitTillMaruHasPeers(1u)
    validatorStack.maruApp.awaitTillMaruHasPeers(1u)

    repeat(blocksToProduce) {
      transactionsHelper.run {
        validatorStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    checkValidatorAndFollowerBlocks(blocksToProduce * 2)
  }

  @Test
  fun `Maru follower is able to import blocks after Validator stack goes down`() {
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

    val validatorP2pPort = validatorStack.p2pPort
    // This is here mainly to wait until block propagation is complete
    checkValidatorAndFollowerBlocks(blocksToProduce)

    validatorStack.maruApp.stop()
    validatorStack.maruApp.close()
    validatorStack.setMaruApp(
      maruFactory.buildTestMaruValidatorWithP2pPeering(
        ethereumJsonRpcUrl = validatorStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = validatorStack.besuNode.engineRpcUrl().get(),
        dataDir = validatorStack.tmpDir,
        p2pPort = validatorP2pPort,
      ),
    )
    validatorStack.maruApp.start()

    repeat(blocksToProduce) {
      transactionsHelper.run {
        validatorStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    checkValidatorAndFollowerBlocks(blocksToProduce * 2)
  }

  @Test
  fun `Maru follower is able to import blocks after its validator el node goes down`() {
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

    // This is here mainly to wait until block propagation is complete
    checkValidatorAndFollowerBlocks(blocksToProduce)

    cluster.stop()
    Thread.sleep(3000)
    cluster.startWithRetry(followerStack.besuNode)

    repeat(blocksToProduce) {
      transactionsHelper.run {
        validatorStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    checkValidatorAndFollowerBlocks(blocksToProduce * 2)
  }

  @ParameterizedTest
  @MethodSource("enumeratingSyncingConfigs")
  fun `Maru follower is able to complete initial syncing`(syncingConfig: SyncingConfig) {
    setupMaruHelper(syncingConfig)

    followerStack.maruApp.stop()
    followerStack.maruApp.close()

    val residueBlocks = 3 // residue of modulo peerChainHeightGranularity i.e. 10
    val blocksToProduceWithoutResidue = 20 // a block number dividable by 10
    val blocksTotal = residueBlocks + blocksToProduceWithoutResidue

    repeat(blocksTotal) {
      transactionsHelper.run {
        validatorStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    // This is here mainly to wait until block propagation is complete
    checkNetworkStacksBlocksProduced(blocksTotal, validatorStack)

    followerStack.setMaruApp(
      maruFactory.buildTestMaruFollowerWithP2pPeering(
        ethereumJsonRpcUrl = followerStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = followerStack.besuNode.engineRpcUrl().get(),
        dataDir = followerStack.tmpDir,
        validatorPortForStaticPeering = validatorStack.p2pPort,
        syncingConfig = syncingConfig,
      ),
    )
    followerStack.maruApp.start()

    when (syncingConfig.syncTargetSelection) {
      is SyncingConfig.SyncTargetSelection.Highest ->
        checkValidatorAndFollowerBlocks(blocksTotal)

      is SyncingConfig.SyncTargetSelection.MostFrequent -> {
        checkValidatorAndFollowerBlocks(blocksToProduceWithoutResidue)
        // ensure that the head of follower is blocksToProduceWithoutResidue
        assertThat(followerStack.besuNode.getBlockNumber()).isEqualTo(blocksToProduceWithoutResidue)
      }
    }
  }

  @ParameterizedTest
  @MethodSource("enumeratingSyncingConfigs")
  fun `Maru follower is able to complete syncing after restarted`(syncingConfig: SyncingConfig) {
    setupMaruHelper(syncingConfig)

    val residueBlocks = 3 // residue of modulo peerChainHeightGranularity i.e. 10
    val blocksToProduceWithoutResidue = 10 // a block number dividable by 10

    repeat(blocksToProduceWithoutResidue) {
      transactionsHelper.run {
        validatorStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    // This is here mainly to wait until block propagation is complete
    checkValidatorAndFollowerBlocks(blocksToProduceWithoutResidue)

    followerStack.maruApp.stop()
    followerStack.maruApp.close()

    repeat(blocksToProduceWithoutResidue + residueBlocks) {
      transactionsHelper.run {
        validatorStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }
    checkNetworkStacksBlocksProduced(2 * blocksToProduceWithoutResidue + residueBlocks, validatorStack)

    followerStack.setMaruApp(
      maruFactory.buildTestMaruFollowerWithP2pPeering(
        ethereumJsonRpcUrl = followerStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = followerStack.besuNode.engineRpcUrl().get(),
        dataDir = followerStack.tmpDir,
        validatorPortForStaticPeering = validatorStack.p2pPort,
        syncingConfig = syncingConfig,
      ),
    )
    followerStack.maruApp.start()

    when (syncingConfig.syncTargetSelection) {
      is SyncingConfig.SyncTargetSelection.Highest ->
        checkValidatorAndFollowerBlocks(
          2 * blocksToProduceWithoutResidue + residueBlocks,
        )

      is SyncingConfig.SyncTargetSelection.MostFrequent -> {
        checkValidatorAndFollowerBlocks(2 * blocksToProduceWithoutResidue)
        // ensure that the head of follower is 2 * blocksToProduceWithoutResidue
        assertThat(followerStack.besuNode.getBlockNumber()).isEqualTo(2 * blocksToProduceWithoutResidue)
      }
    }
  }

  @ParameterizedTest
  @MethodSource("enumeratingSyncingConfigs")
  fun `Maru follower is able to complete syncing after disconnect peers`(syncingConfig: SyncingConfig) {
    setupMaruHelper(syncingConfig)

    val residueBlocks = 3 // residue of modulo peerChainHeightGranularity i.e. 10
    val blocksToProduce = 20 // a block number dividable by 10
    repeat(blocksToProduce) {
      transactionsHelper.run {
        validatorStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    // This is here mainly to wait until block propagation is complete
    checkValidatorAndFollowerBlocks(blocksToProduce)

    val followerP2PNetwork = followerStack.maruApp.p2pNetwork()
    val peers = followerP2PNetwork.getPeers()
    peers.forEach {
      followerP2PNetwork.dropPeer(it)
    }

    repeat(blocksToProduce + residueBlocks) {
      transactionsHelper.run {
        validatorStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }
    checkNetworkStacksBlocksProduced(2 * blocksToProduce + residueBlocks, validatorStack)
    checkNetworkStacksBlocksProduced(blocksToProduce, followerStack)
    // ensure that the head of follower is at blocksToProduce
    assertThat(followerStack.besuNode.getBlockNumber()).isEqualTo(blocksToProduce)

    peers.forEach {
      followerP2PNetwork.addPeer("${it.address}/p2p/${it.nodeId}")
    }
    when (syncingConfig.syncTargetSelection) {
      is SyncingConfig.SyncTargetSelection.Highest ->
        checkValidatorAndFollowerBlocks(
          2 * blocksToProduce + residueBlocks,
        )

      is SyncingConfig.SyncTargetSelection.MostFrequent -> {
        checkValidatorAndFollowerBlocks(2 * blocksToProduce)
        // ensure that the head of follower is at 2 * blocksToProduce
        assertThat(followerStack.besuNode.getBlockNumber()).isEqualTo(2 * blocksToProduce)
      }
    }
  }

  private fun checkValidatorAndFollowerBlocks(blocksToProduce: Int) {
    await
      .pollDelay(1.seconds.toJavaDuration())
      .timeout(30.seconds.toJavaDuration())
      .untilAsserted {
        val blocksProducedByQbftValidator = blocksToMetadata(validatorStack.besuNode.getMinedBlocks(blocksToProduce))
        val blocksImportedByFollower = blocksToMetadata(followerStack.besuNode.getMinedBlocks(blocksToProduce))
        assertThat(blocksProducedByQbftValidator)
          .hasSize(blocksToProduce)
        assertThat(blocksImportedByFollower)
          .hasSize(blocksToProduce)
        assertThat(blocksImportedByFollower)
          .isEqualTo(blocksProducedByQbftValidator)
      }
  }

  private fun blocksToMetadata(blocks: List<EthBlock.Block>): List<Pair<BigInteger, String>> =
    blocks.map {
      it.number to it.hash
    }

  private fun checkNetworkStacksBlocksProduced(
    blocksProduced: Int,
    vararg stacks: PeeringNodeNetworkStack,
  ) {
    await
      .pollDelay(1.seconds.toJavaDuration())
      .timeout(30.seconds.toJavaDuration())
      .untilAsserted {
        if (stacks.isNotEmpty()) {
          val referenceBlocks = blocksToMetadata(stacks.first().besuNode.getMinedBlocks(blocksProduced))
          if (stacks.size == 1) {
            assertThat(referenceBlocks.size).isEqualTo(blocksProduced)
          } else {
            stacks.drop(1).map {
              val checkedBlocks = blocksToMetadata(it.besuNode.getMinedBlocks(blocksProduced))
              assertThat(checkedBlocks).isEqualTo(referenceBlocks)
            }
          }
        }
      }
  }
}
