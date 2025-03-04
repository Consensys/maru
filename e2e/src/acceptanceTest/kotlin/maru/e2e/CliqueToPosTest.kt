/*
   Copyright 2025 Consensys Software Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package maru.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.palantir.docker.compose.DockerComposeRule
import com.palantir.docker.compose.configuration.ProjectName
import com.palantir.docker.compose.connection.waiting.HealthChecks
import java.io.File
import java.math.BigInteger
import java.nio.file.Path
import java.util.Optional
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import maru.e2e.Mappers.executionPayloadV1FromBlock
import maru.e2e.TestEnvironment.createWeb3jClient
import maru.e2e.TestEnvironment.waitForInclusion
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes32
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.response.EthBlock
import tech.pegasys.teku.ethereum.executionclient.auth.JwtConfig
import tech.pegasys.teku.ethereum.executionclient.schema.ExecutionPayloadV1
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceStateV1
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JExecutionEngineClient
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.unsigned.UInt64

class CliqueToPosTest {
  companion object {
    private val qbftCluster =
      DockerComposeRule
        .Builder()
        .file(Path.of("./../docker/compose.yaml").toString())
        .projectName(ProjectName.random())
        .waitingForService("sequencer", HealthChecks.toHaveAllPortsOpen())
        .build()
    private val maru = MaruFactory.buildTestMaru()

    @BeforeAll
    @JvmStatic
    fun beforeAll() {
      qbftCluster.before()
    }

    @AfterAll
    @JvmStatic
    fun afterAll() {
      qbftCluster.after()
    }

    private fun createExecutionClient(
      eeEndpoint: String,
      jwtConfig: Optional<JwtConfig> = Optional.empty(),
    ): Web3JExecutionEngineClient = Web3JExecutionEngineClient(createWeb3jClient(eeEndpoint, jwtConfig))

    private val log: Logger = LogManager.getLogger(CliqueToPosTest::class.java)
    private val besuFollowerExecutionEngineClient = createExecutionClient("http://localhost:9550")
    private val nethermindFollowerExecutionEngineClient =
      createExecutionClient(
        "http://localhost:10550",
        TestEnvironment.jwtConfig,
      )
    private val erigonFollowerExecutionEngineClient =
      createExecutionClient(
        "http://localhost:11551",
        TestEnvironment.jwtConfig,
      )
    private val geth1ExecutionEngineClient = createExecutionClient("http://localhost:8561", TestEnvironment.jwtConfig)
    private val geth2ExecutionEngineClient = createExecutionClient("http://localhost:8571", TestEnvironment.jwtConfig)
    private val gethSnapServerExecutionEngineClient =
      createExecutionClient("http://localhost:8581", TestEnvironment.jwtConfig)
    private val gethExecutionEngineClients =
      mapOf(
//        "follower-geth" to geth1ExecutionEngineClient,
        "follower-geth-2" to geth2ExecutionEngineClient,
        "follower-geth-snap-server" to gethSnapServerExecutionEngineClient,
      )
    private val followerExecutionEngineClients =
      mapOf(
        "follower-besu" to besuFollowerExecutionEngineClient,
        "follower-erigon" to erigonFollowerExecutionEngineClient,
        "follower-nethermind" to nethermindFollowerExecutionEngineClient,
      ) + gethExecutionEngineClients

    @JvmStatic
    fun followerNodes(): List<Arguments> =
      followerExecutionEngineClients
        .filter {
          // Doesn't work just yet
          !it.key.contains("nethermind")
        }.map {
          Arguments.of(it.key, it.value)
        }
  }

  @Order(1)
  @Test
  fun networkCanBeSwitched() {
    maru.start()
    sealPreMergeBlocks()
    everyoneArePeered()
    val newBlockTimestamp = UInt64.valueOf(parseSwitchTimestamp())

    await
      .timeout(1.minutes.toJavaDuration())
      .pollInterval(5.seconds.toJavaDuration())
      .untilAsserted {
        val unixTimestamp = System.currentTimeMillis() / 1000
        log.info(
          "Waiting for the switch {} seconds until the switch ",
          { newBlockTimestamp.longValue() - unixTimestamp },
        )
        assertThat(unixTimestamp).isGreaterThan(newBlockTimestamp.longValue())
      }

    log.info("Marked last pre merge block as finalized")

    // Next block's content
    TestEnvironment.sendArbitraryTransaction().waitForInclusion()

    log.info("Sequencer has switched to PoS")

    TestEnvironment.sendArbitraryTransaction().waitForInclusion()

    assertNodeBlockHeight(7, TestEnvironment.sequencerL2Client)
    setAllFollowersHeadToBlockNumber(6)
    setAllFollowersHeadToBlockNumber(7)

    waitForAllBlockHeightsToMatch()
    maru.stop()
  }

  @Order(2)
  @ParameterizedTest
  @MethodSource("followerNodes")
  fun syncFromScratch(
    nodeName: String,
    nodeEngineApiClient: Web3JExecutionEngineClient,
  ) {
    qbftCluster.docker().rm("${qbftCluster.projectName().asString()}-$nodeName-1")

    qbftCluster.dockerCompose().up()
    val nodeEthereumClient = TestEnvironment.followerClients[nodeName]!!

    await
      .timeout(20.seconds.toJavaDuration())
      .ignoreExceptions()
      .alias(nodeName)
      .untilAsserted {
        assertThat(
          nodeEthereumClient
            .ethBlockNumber()
            .send()
            .blockNumber
            .toLong(),
        ).isLessThan(7)
          .withFailMessage("Node is unexpectedly synced after restart! Was its state flushed?")
      }
    if (nodeName.contains("erigon")) {
      // Erigon doesn't seem to backfill the blocks from head to the switch block
      sendNewPayloadByBlockNumber(6, nodeEngineApiClient)
    }

    await.pollInterval(1.seconds.toJavaDuration()).timeout(10.seconds.toJavaDuration()).alias(nodeName).untilAsserted {
      syncTarget(nodeEngineApiClient, 7)
      assertNodeBlockHeight(7, nodeEthereumClient)
    }
  }

  private fun sendNewPayloadByBlockNumber(
    blockNumber: Long,
    target: Web3JExecutionEngineClient,
  ): ExecutionPayloadV1 {
    val targetBlock = getBlockByNumber(blockNumber, true)
    val blockPayload = executionPayloadV1FromBlock(targetBlock)
    val newPayloadResult = target.newPayloadV1(blockPayload).get()
    log.debug("New payload result: $newPayloadResult")
    return blockPayload
  }

  private fun syncTarget(
    target: Web3JExecutionEngineClient,
    headBlockNumber: Long,
  ) {
    val headPayload = sendNewPayloadByBlockNumber(headBlockNumber, target)

    val fcuResult =
      target
        .forkChoiceUpdatedV1(
          ForkChoiceStateV1(headPayload.blockHash, headPayload.blockHash, headPayload.blockHash),
          Optional.empty(),
        ).get()

    log.debug("Fork choice updated result: $fcuResult")
  }

  private fun setAllFollowersHeadToBlockNumber(blockNumber: Long): String {
    val postMergeBlock = getBlockByNumber(blockNumber, true)
    val getNewPayloadFromPostMergeBlockNumber = executionPayloadV1FromBlock(postMergeBlock)
    sendNewPayloadToFollowers(getNewPayloadFromPostMergeBlockNumber)
    fcuFollowersToBlockHash(postMergeBlock.hash)
    return postMergeBlock.hash
  }

  private fun parseSwitchTimestamp(): Long {
    val objectMapper = ObjectMapper()
    val genesisTree = objectMapper.readTree(File("../docker/initialization/genesis-besu.json"))
    val switchTime = genesisTree.at("/config/shanghaiTime").asLong()
    return if (switchTime == 0L) System.currentTimeMillis() / 1000 else switchTime
  }

  private fun sealPreMergeBlocks() {
    val sequencerBlock = TestEnvironment.sequencerL2Client.ethBlockNumber().send()
    if (sequencerBlock.blockNumber >= BigInteger.valueOf(5)) {
      return
    }
    repeat(5) { TestEnvironment.sendArbitraryTransaction().waitForInclusion() }
  }

  private fun assertNodeBlockHeight(
    expectedBlockNumber: Long,
    web3j: Web3j,
  ) {
    val targetNodeBlockHeight =
      web3j
        .ethBlockNumber()
        .send()
        .blockNumber
    assertThat(targetNodeBlockHeight).isEqualTo(expectedBlockNumber)
  }

  private fun waitForAllBlockHeightsToMatch() {
    val sequencerBlockHeight = TestEnvironment.sequencerL2Client.ethBlockNumber().send()

    await.untilAsserted {
      val blockHeights =
        TestEnvironment.followerClients.entries
          .map { entry ->
            entry.key to
              SafeFuture.of(
                entry.value.ethBlockNumber().sendAsync(),
              )
          }.map { it.first to it.second.get() }

      blockHeights.forEach {
        assertThat(it.second.blockNumber)
          .withFailMessage {
            "Block height doesn't match for ${it.first}. Found ${it.second.blockNumber} " +
              "while expecting ${sequencerBlockHeight.blockNumber}."
          }.isEqualTo(sequencerBlockHeight.blockNumber)
      }
    }
  }

  private fun everyoneArePeered() {
    log.info("Call add peer on all nodes and wait for peering to happen.")
    await.pollInterval(1.seconds.toJavaDuration()).timeout(1.minutes.toJavaDuration()).untilAsserted {
      TestEnvironment.followerClients.forEach {
        try {
          it.value
            .adminAddPeer(
              "enode://14408801a444dafc44afbccce2eb755f902aed3b5743fed787b3c790e021fef28b8c827ed896aa4e8fb46e2" +
                "2bd67c39f994a73768b4b382f8597b0d44370e15d@11.11.11.101:30303",
            ).send()
        } catch (e: Exception) {
          if (it.key.contains("nethermind")) {
            log.debug("Nethermind returns response to admin_addPeer that is incompatible with Web3J")
          } else {
            throw e
          }
        }
        val peersResult =
          it.value
            .adminPeers()
            .send()
            .result
        val peers = peersResult.size
        log.info("Peers from node ${it.key}: $peers")
        assertThat(peers).withFailMessage("${it.key} isn't peered! Peers: $peersResult").isGreaterThan(0)
      }
    }
  }

  private fun getBlockByNumber(
    blockNumber: Long,
    retreiveTransactions: Boolean = false,
  ): EthBlock.Block =
    TestEnvironment.sequencerL2Client
      .ethGetBlockByNumber(
        DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)),
        retreiveTransactions,
      ).send()
      .block

  private fun fcuFollowersToBlockHash(blockHash: String) {
    val lastBlockHashBytes = Bytes32.fromHexString(blockHash)
    // Cutting off the merge first
    val mergeForkChoiceState = ForkChoiceStateV1(lastBlockHashBytes, lastBlockHashBytes, lastBlockHashBytes)

    followerExecutionEngineClients.entries
      .map {
        it.key to
          it.value.forkChoiceUpdatedV1(
            mergeForkChoiceState,
            Optional.empty(),
          )
      }.forEach {
        log.info("FCU for block hash $blockHash, node: ${it.first} response: ${it.second.get()}")
      }
  }

  private fun sendNewPayloadToFollowers(newPayloadV1: ExecutionPayloadV1) {
    followerExecutionEngineClients.entries
      .map {
        it.key to
          it.value.newPayloadV1(
            newPayloadV1,
          )
      }.forEach { log.info("New payload for node: ${it.first} response: ${it.second.get()}") }
  }
}
