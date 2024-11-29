package maru.e2e

import maru.e2e.TestEnvironment.waitForInclusion
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes32
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import tech.pegasys.teku.ethereum.executionclient.auth.JwtConfig
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceStateV1
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadAttributesV3
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JExecutionEngineClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3jClientBuilder
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.bytes.Bytes20
import tech.pegasys.teku.infrastructure.time.SystemTimeProvider
import tech.pegasys.teku.infrastructure.unsigned.UInt64
import java.io.File
import java.math.BigInteger
import java.util.*
import kotlin.io.path.Path
import kotlin.test.Ignore
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class SwitchTest {
    private val log: Logger = LogManager.getLogger(SwitchTest::class.java)
    private val web3jClient = createWeb3jClient("http://localhost:8550", Optional.empty())
    private val sequencerExecutionClient = Web3JExecutionEngineClient(web3jClient)
    private val jwtConfig = JwtConfig.createIfNeeded(
        true,
        Optional.of("../docker/geth/jwt"),
        Optional.of(UUID.randomUUID().toString()),
        Path("/tmp")
    )
    private val besuFollowerExecutionEngineClient = createExecutionClient("http://localhost:9550")
    private val geth1ExecutionEngineClient = createExecutionClient("http://localhost:8561", jwtConfig)
    private val geth2ExecutionEngineClient = createExecutionClient("http://localhost:8571", jwtConfig)
    private val gethSnapServerExecutionEngineClient = createExecutionClient("http://localhost:8581", jwtConfig)
    private val gethExecutuionEngineClients = mapOf(
        "geth1" to geth1ExecutionEngineClient,
        "geth2" to geth2ExecutionEngineClient,
        "gethSnapServer" to gethSnapServerExecutionEngineClient
    )
    private val followerExecutionEngineClients = mapOf(
        "besu" to besuFollowerExecutionEngineClient
    ) + gethExecutuionEngineClients

    @Test
    fun networkCanBeSwitched() {
        everyoneArePeered()

//        sealPreMergeBlocks()

        val newBlockTimestamp = UInt64.valueOf(parseCancunTimestamp())

        await()
            .timeout(1.minutes.toJavaDuration())
            .pollInterval(5.seconds.toJavaDuration())
            .untilAsserted {
                val unixTimestamp = System.currentTimeMillis() / 1000
                log.info("Waiting for Cancun switch ${newBlockTimestamp.longValue() - unixTimestamp} seconds until the switch ")
                assertThat(unixTimestamp).isGreaterThan(newBlockTimestamp.longValue())
            }

        waitForAllBlockHeightsToMatch()

        val preMergeBlock =
            TestEnvironment.sequencerL2Client.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send()
        val lastPreMergeBlockHash = preMergeBlock.block.hash
        val lastPreMergeBlockHashBytes32 = Bytes32.fromHexString(lastPreMergeBlockHash)

        fcuFollowersToBlockHash(lastPreMergeBlockHash)

        log.info("Marked last pre merge block as finalized")

        // Next block's content
        TestEnvironment.sendArbitraryTransaction()

        val payloadAttributes = Optional.of(
            PayloadAttributesV3(
                newBlockTimestamp,
                Bytes32.ZERO,
                Bytes20.fromHexString("0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0"),
                emptyList(),
                Bytes32.ZERO
            )
        )
        val payloadId = sequencerExecutionClient.forkChoiceUpdatedV3(
            ForkChoiceStateV1(
                lastPreMergeBlockHashBytes32,
                lastPreMergeBlockHashBytes32,
                lastPreMergeBlockHashBytes32
            ), payloadAttributes
        ).get().payload.asInternalExecutionPayload().payloadId
        val getPayloadResponse = sequencerExecutionClient.getPayloadV3(payloadId.get()).get()
        val newExecutionPayload = getPayloadResponse.payload.executionPayload
        val newPayloadResult =
            sequencerExecutionClient.newPayloadV3(newExecutionPayload, emptyList(), Bytes32.ZERO).get()
        val newPayloadHash = newPayloadResult.payload.asInternalExecutionPayload().latestValidHash.get()
        val nextPayloadAttributes = Optional.of(
            PayloadAttributesV3(
                newExecutionPayload.timestamp + 1,
                Bytes32.ZERO,
                Bytes20.fromHexString("0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0"),
                emptyList(),
                Bytes32.ZERO
            )
        )
        val nextForkChoiceState = ForkChoiceStateV1(newPayloadHash, newPayloadHash, newPayloadHash)
        sequencerExecutionClient.forkChoiceUpdatedV3(nextForkChoiceState, nextPayloadAttributes).get().also {
            log.info("FCU of new block on Sequencer $it")
        }

        log.info("Sequencer has switched to PoS")

        gethExecutuionEngineClients.map {
            it.key to it.value.newPayloadV3(newExecutionPayload, emptyList(), Bytes32.ZERO)
        }.forEach {
            log.info("Sent new payload to ${it.first}, response: ${it.second.get()}")
        }

        await().untilAsserted {
            fcuFollowersToBlockHash(newPayloadHash.toHexString())
            waitForAllBlockHeightsToMatch()
        }
    }

    @Ignore
    fun runFCU() {
        val currentBLock =
            TestEnvironment.sequencerL2Client.ethGetBlockByNumber(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(5L)),
                false
            ).send()
        val blockHash = currentBLock.block.hash
        fcuFollowersToBlockHash(blockHash)
    }

    private fun parseCancunTimestamp(): Long {
        val objectMapper = ObjectMapper()
        val genesisTree = objectMapper.readTree(File("../docker/genesis-besu.json"))
        return genesisTree.at("/config/shanghaiTime").asLong()
    }

    private fun sealPreMergeBlocks() {
        repeat(5) { TestEnvironment.sendArbitraryTransaction().waitForInclusion() }
    }

    private fun createWeb3jClient(eeEndpoint: String, jwtConfig: Optional<JwtConfig>): Web3JClient {
        return Web3jClientBuilder()
            .timeout(1.minutes.toJavaDuration())
            .endpoint(eeEndpoint)
            .jwtConfigOpt(jwtConfig)
            .timeProvider(SystemTimeProvider.SYSTEM_TIME_PROVIDER)
            .executionClientEventsPublisher { }
            .build()
    }

    private fun createExecutionClient(
        eeEndpoint: String,
        jwtConfig: Optional<JwtConfig> = Optional.empty()
    ): Web3JExecutionEngineClient {
        return Web3JExecutionEngineClient(createWeb3jClient(eeEndpoint, jwtConfig))
    }

    private fun waitForAllBlockHeightsToMatch() {
        val sequencerBlockHeight = TestEnvironment.sequencerL2Client.ethBlockNumber().send()

        await().untilAsserted {
            val blockHeights = TestEnvironment.followerClients.entries.map { entry ->
                entry.key to SafeFuture.of(entry.value.ethBlockNumber().sendAsync())
            }.map {
                it.first to it.second.get()
            }

            blockHeights.forEach {
                assertThat(it.second.blockNumber)
                    .withFailMessage { "Block height doesn't match for ${it.first}" }
                    .isEqualTo(sequencerBlockHeight.blockNumber)
            }
        }
    }

    private fun everyoneArePeered() {
        await()
            .pollInterval(1.seconds.toJavaDuration())
            .timeout(1.minutes.toJavaDuration())
            .untilAsserted {
                TestEnvironment.followerClients.map {
                    it.key to it.value.adminPeers().sendAsync()
                }.forEach{
                    val peersResult = it.second.get().result
                    val peers = peersResult.size
                    log.info("Peers from node ${it.first}: $peers")
                    assertThat(peers)
                        .withFailMessage("${it.first} isn't peered! Peers: $peersResult")
                        .isGreaterThan(0)
                }
            }
    }

    private fun fcuFollowersToBlockHash(blockHash: String) {
        val lastBlockHashBytes = Bytes32.fromHexString(blockHash)
        // Cutting off the merge first
        val mergeForkChoiceState = ForkChoiceStateV1(lastBlockHashBytes, lastBlockHashBytes, lastBlockHashBytes)

        followerExecutionEngineClients.entries.map {
            it.key to it.value.forkChoiceUpdatedV3(mergeForkChoiceState, Optional.empty())
        }.forEach {
            log.info("FCU for block hash ${blockHash}, node: ${it.first} response: ${it.second.get()}")
        }
    }
}