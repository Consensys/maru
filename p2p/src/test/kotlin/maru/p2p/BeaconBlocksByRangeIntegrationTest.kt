/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import io.libp2p.core.PeerId
import java.util.concurrent.TimeUnit
import maru.config.P2P
import maru.config.consensus.ElFork
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ConsensusConfig
import maru.consensus.ForkIdHashProvider
import maru.consensus.ForkIdHasher
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import maru.core.SealedBeaconBlock
import maru.core.ext.DataGenerators
import maru.core.ext.metrics.TestMetrics
import maru.crypto.Hashing
import maru.database.BeaconChain
import maru.database.InMemoryBeaconChain
import maru.p2p.messages.BeaconBlocksByRangeRequest
import maru.p2p.messages.BeaconBlocksByRangeResponse
import maru.p2p.messages.StatusMessageFactory
import maru.serialization.ForkIdSerializers
import maru.serialization.rlp.RLPSerializers
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNodeId

@Execution(ExecutionMode.SAME_THREAD)
class BeaconBlocksByRangeIntegrationTest {
  companion object {
    private val chainId = 1337u
    private const val IPV4 = "127.0.0.1"
    private const val PORT1 = 9334u
    private const val PORT2 = 9335u

    private const val PRIVATE_KEY1: String =
      "0x0802122012c0b113e2b0c37388e2b484112e13f05c92c4471e3ee1dfaa368fa5045325b2"
    private const val PRIVATE_KEY2: String =
      "0x0802122100f3d2fffa99dc8906823866d96316492ebf7a8478713a89a58b7385af85b088a1"

    private const val PEER_ID_NODE_1: String = "16Uiu2HAmPRfinavM2jE9BSkCagBGStJ2SEkPPm6fxFVMdCQebzt6"
    private const val PEER_ADDRESS_NODE_1: String = "/ip4/$IPV4/tcp/$PORT1/p2p/$PEER_ID_NODE_1"

    private val key1 = Bytes.fromHexString(PRIVATE_KEY1).toArray()
    private val key2 = Bytes.fromHexString(PRIVATE_KEY2).toArray()
  }

  private lateinit var beaconChain1: BeaconChain
  private lateinit var beaconChain2: BeaconChain
  private lateinit var p2pNetwork1: P2PNetworkImpl
  private lateinit var p2pNetwork2: P2PNetworkImpl
  private lateinit var storedBlocks: List<SealedBeaconBlock>

  @BeforeEach
  fun setup() {
    // Set up beacon chains with some blocks
    val initialState = DataGenerators.randomBeaconState(number = 0u, timestamp = 0u)
    beaconChain1 = InMemoryBeaconChain(initialState)
    beaconChain2 = InMemoryBeaconChain(initialState)

    // Store some blocks in beacon chain 1
    storedBlocks =
      (1UL..10UL).map { blockNumber ->
        DataGenerators.randomSealedBeaconBlock(number = blockNumber)
      }

    beaconChain1.newUpdater().use { updater ->
      storedBlocks.forEach { block ->
        updater.putSealedBeaconBlock(block)
      }
      updater.commit()
    }

    val forkIdHashProvider1 = createForkIdHashProvider(beaconChain1)
    val forkIdHashProvider2 = createForkIdHashProvider(beaconChain2)

    val statusMessageFactory1 = StatusMessageFactory(beaconChain1, forkIdHashProvider1)
    val statusMessageFactory2 = StatusMessageFactory(beaconChain2, forkIdHashProvider2)

    p2pNetwork1 =
      P2PNetworkImpl(
        privateKeyBytes = key1,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT1, staticPeers = emptyList()),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory1,
        beaconChain = beaconChain1,
        nextExpectedBeaconBlockNumber = 1UL,
      )

    p2pNetwork2 =
      P2PNetworkImpl(
        privateKeyBytes = key2,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT2, staticPeers = listOf(PEER_ADDRESS_NODE_1)),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory2,
        beaconChain = beaconChain2,
        nextExpectedBeaconBlockNumber = 1UL,
      )
  }

  @AfterEach
  fun tearDown() {
    p2pNetwork1.stop().get(5, TimeUnit.SECONDS)
    p2pNetwork2.stop().get(5, TimeUnit.SECONDS)
  }

  @Test
  fun `peer can request and receive blocks by range`() {
    // Start networks
    p2pNetwork1.start().get()
    p2pNetwork2.start().get()

    // Wait for peers to connect
    awaitUntilAsserted {
      assertThat(p2pNetwork1.peerCount).isEqualTo(1)
      assertThat(p2pNetwork2.peerCount).isEqualTo(1)
    }

    // Get peer reference
    val peer1 =
      p2pNetwork2.peerLookup.getPeer(LibP2PNodeId(PeerId.fromBase58(PEER_ID_NODE_1)))
        ?: throw IllegalStateException("Peer not found")
    val maruPeer1 =
      DefaultMaruPeer(
        peer1,
        createRpcMethods(beaconChain2),
        StatusMessageFactory(beaconChain2, createForkIdHashProvider(beaconChain2)),
      )

    // Send request for blocks
    val request = BeaconBlocksByRangeRequest(startBlockNumber = 3UL, count = 5UL)
    val requestMessage =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = request,
      )

    val responseHandler = MaruRpcResponseHandler<Message<BeaconBlocksByRangeResponse, RpcMessageType>>()

    peer1.sendRequest(
      createRpcMethods(beaconChain2).beaconBlocksByRange(),
      requestMessage,
      responseHandler,
    )

    val responseMessage = responseHandler.response().get(5, TimeUnit.SECONDS)
    val response = responseMessage.payload

    // Verify response
    assertThat(response.blocks).hasSize(5)
    assertThat(
      response.blocks[0]
        .beaconBlock.beaconBlockHeader.number,
    ).isEqualTo(3UL)
    assertThat(
      response.blocks[1]
        .beaconBlock.beaconBlockHeader.number,
    ).isEqualTo(4UL)
    assertThat(
      response.blocks[2]
        .beaconBlock.beaconBlockHeader.number,
    ).isEqualTo(5UL)
    assertThat(
      response.blocks[3]
        .beaconBlock.beaconBlockHeader.number,
    ).isEqualTo(6UL)
    assertThat(
      response.blocks[4]
        .beaconBlock.beaconBlockHeader.number,
    ).isEqualTo(7UL)

    // Verify the actual block content matches
    assertThat(response.blocks[0]).isEqualTo(storedBlocks[2]) // block 3 is at index 2
    assertThat(response.blocks[1]).isEqualTo(storedBlocks[3])
    assertThat(response.blocks[2]).isEqualTo(storedBlocks[4])
    assertThat(response.blocks[3]).isEqualTo(storedBlocks[5])
    assertThat(response.blocks[4]).isEqualTo(storedBlocks[6])
  }

  @Test
  fun `peer receives empty response when requesting non-existent blocks`() {
    // Start networks
    p2pNetwork1.start().get()
    p2pNetwork2.start().get()

    // Wait for peers to connect
    awaitUntilAsserted {
      assertThat(p2pNetwork1.peerCount).isEqualTo(1)
      assertThat(p2pNetwork2.peerCount).isEqualTo(1)
    }

    // Get peer reference
    val peer1 =
      p2pNetwork2.peerLookup.getPeer(LibP2PNodeId(PeerId.fromBase58(PEER_ID_NODE_1)))
        ?: throw IllegalStateException("Peer not found")

    // Send request for blocks beyond what exists
    val request = BeaconBlocksByRangeRequest(startBlockNumber = 100UL, count = 10UL)
    val requestMessage =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = request,
      )

    val responseHandler = MaruRpcResponseHandler<Message<BeaconBlocksByRangeResponse, RpcMessageType>>()

    peer1.sendRequest(
      createRpcMethods(beaconChain2).beaconBlocksByRange(),
      requestMessage,
      responseHandler,
    )

    val responseMessage = responseHandler.response().get(5, TimeUnit.SECONDS)
    val response = responseMessage.payload

    // Verify empty response
    assertThat(response.blocks).isEmpty()
  }

  private fun createForkIdHashProvider(beaconChain: BeaconChain): ForkIdHashProvider {
    val consensusConfig: ConsensusConfig =
      QbftConsensusConfig(
        validatorSet =
          setOf(
            DataGenerators.randomValidator(),
            DataGenerators.randomValidator(),
          ),
        elFork = ElFork.Prague,
      )
    val forksSchedule = ForksSchedule(chainId, listOf(ForkSpec(0L, 1, consensusConfig)))

    return ForkIdHashProvider(
      chainId = chainId,
      beaconChain = beaconChain,
      forksSchedule = forksSchedule,
      forkIdHasher = ForkIdHasher(ForkIdSerializers.ForkIdSerializer, Hashing::shortShaHash),
    )
  }

  private fun createRpcMethods(beaconChain: BeaconChain): RpcMethods {
    val rpcProtocolIdGenerator = LineaRpcProtocolIdGenerator(chainId)
    lateinit var maruPeerManager: MaruPeerManager
    val rpcMethods =
      RpcMethods(
        StatusMessageFactory(beaconChain, createForkIdHashProvider(beaconChain)),
        rpcProtocolIdGenerator,
        { maruPeerManager },
        beaconChain,
      )
    val maruPeerFactory =
      DefaultMaruPeerFactory(rpcMethods, StatusMessageFactory(beaconChain, createForkIdHashProvider(beaconChain)))
    maruPeerManager = MaruPeerManager(maruPeerFactory = maruPeerFactory)
    return rpcMethods
  }

  private fun awaitUntilAsserted(
    timeout: Long = 10000L,
    timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    condition: () -> Unit,
  ) {
    await()
      .timeout(timeout, timeUnit)
      .untilAsserted(condition)
  }
}
