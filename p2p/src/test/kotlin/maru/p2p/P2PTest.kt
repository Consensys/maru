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
import java.lang.Thread.sleep
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
import maru.p2p.messages.Status
import maru.p2p.messages.StatusMessageFactory
import maru.serialization.ForkIdSerializers
import maru.serialization.rlp.RLPSerializers
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatNoException
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNodeId
import tech.pegasys.teku.networking.p2p.libp2p.MultiaddrPeerAddress
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason

@Execution(ExecutionMode.SAME_THREAD)
class P2PTest {
  companion object {
    private val chainId = 1337u

    private const val IPV4 = "127.0.0.1"

    private const val PORT1 = 9234u
    private const val PORT2 = 9235u
    private const val PORT3 = 9236u

    private const val PRIVATE_KEY1: String =
      "0x0802122012c0b113e2b0c37388e2b484112e13f05c92c4471e3ee1dfaa368fa5045325b2"
    private const val PRIVATE_KEY2: String =
      "0x0802122100f3d2fffa99dc8906823866d96316492ebf7a8478713a89a58b7385af85b088a1"
    private const val PRIVATE_KEY3: String =
      "0x080212204437acb8e84bc346f7640f239da84abe99bc6f97b7855f204e34688d2977fd57"

    private const val PEER_ID_NODE_1: String = "16Uiu2HAmPRfinavM2jE9BSkCagBGStJ2SEkPPm6fxFVMdCQebzt6"
    private const val PEER_ID_NODE_2: String = "16Uiu2HAmVXtqhevTAJqZucPbR2W4nCMpetrQASgjZpcxDEDaUPPt"
    private const val PEER_ID_NODE_3: String = "16Uiu2HAkzq767a82zfyUz4VLgPbFrxSQBrdmUYxgNDbwgvmjwWo5"

    // TODO: to make these tests reliable it would be good if the ports were not hardcoded, but free ports chosen
    private const val PEER_ADDRESS_NODE_1: String = "/ip4/$IPV4/tcp/$PORT1/p2p/$PEER_ID_NODE_1"
    private const val PEER_ADDRESS_NODE_2: String = "/ip4/$IPV4/tcp/$PORT2/p2p/$PEER_ID_NODE_2"
    private const val PEER_ADDRESS_NODE_3: String = "/ip4/$IPV4/tcp/$PORT3/p2p/$PEER_ID_NODE_3"

    private val key1 = Bytes.fromHexString(PRIVATE_KEY1).toArray()
    private val key2 = Bytes.fromHexString(PRIVATE_KEY2).toArray()
    private val key3 = Bytes.fromHexString(PRIVATE_KEY3).toArray()
    private val initialExpectedBeaconBlockNumber = 1UL
    private val beaconChain = InMemoryBeaconChain(DataGenerators.randomBeaconState(number = 0u, timestamp = 0u))
    private val forkIdHashProvider =
      createForkIdHashProvider()
    private val statusMessageFactory = StatusMessageFactory(beaconChain, forkIdHashProvider)
    private val rpcMethods = createRpcMethods()

    fun createRpcMethods(): RpcMethods {
      val rpcProtocolIdGenerator = LineaRpcProtocolIdGenerator(chainId)
      lateinit var maruPeerManager: MaruPeerManager
      val rpcMethods = RpcMethods(statusMessageFactory, rpcProtocolIdGenerator, { maruPeerManager }, beaconChain)
      val maruPeerFactory = DefaultMaruPeerFactory(rpcMethods, statusMessageFactory)
      maruPeerManager = MaruPeerManager(maruPeerFactory = maruPeerFactory)
      return rpcMethods
    }

    fun createRpcMethods(
      testBeaconChain: BeaconChain,
      testStatusMessageFactory: StatusMessageFactory,
    ): RpcMethods {
      val rpcProtocolIdGenerator = LineaRpcProtocolIdGenerator(chainId)
      lateinit var maruPeerManager: MaruPeerManager
      val rpcMethods =
        RpcMethods(testStatusMessageFactory, rpcProtocolIdGenerator, { maruPeerManager }, testBeaconChain)
      val maruPeerFactory = DefaultMaruPeerFactory(rpcMethods, testStatusMessageFactory)
      maruPeerManager = MaruPeerManager(maruPeerFactory = maruPeerFactory)
      return rpcMethods
    }

    fun createForkIdHashProvider(): ForkIdHashProvider {
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
  }

  @Test
  fun `static peer can be added`() {
    val p2PNetworkImpl1 =
      P2PNetworkImpl(
        privateKeyBytes = key1,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT1, staticPeers = emptyList()),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    val p2pNetworkImpl2 =
      P2PNetworkImpl(
        privateKeyBytes = key2,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT2, staticPeers = emptyList()),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    try {
      p2PNetworkImpl1.start()

      p2pNetworkImpl2.start()

      p2PNetworkImpl1.addStaticPeer(MultiaddrPeerAddress.fromAddress(PEER_ADDRESS_NODE_2))

      awaitUntilAsserted { assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }
    } finally {
      p2PNetworkImpl1.stop()
      p2pNetworkImpl2.stop()
    }
  }

  @Test
  fun `static peers can be removed`() {
    val p2PNetworkImpl1 =
      P2PNetworkImpl(
        privateKeyBytes = key1,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT1, staticPeers = emptyList()),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    val p2pNetworkImpl2 =
      P2PNetworkImpl(
        privateKeyBytes = key2,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT2, staticPeers = emptyList()),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    try {
      p2PNetworkImpl1.start()

      p2pNetworkImpl2.start()

      p2PNetworkImpl1.addStaticPeer(MultiaddrPeerAddress.fromAddress(PEER_ADDRESS_NODE_2))

      awaitUntilAsserted { assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      p2PNetworkImpl1.removeStaticPeer(MultiaddrPeerAddress.fromAddress(PEER_ADDRESS_NODE_2))

      awaitUntilAsserted { assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 0) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 0) }
    } finally {
      p2PNetworkImpl1.stop()
      p2pNetworkImpl2.stop()
    }
  }

  @Test
  fun `static peers can be configured`() {
    val p2PNetworkImpl1 =
      P2PNetworkImpl(
        privateKeyBytes = key1,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT1, staticPeers = emptyList()),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    val p2pNetworkImpl2 =
      P2PNetworkImpl(
        privateKeyBytes = key2,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT2, staticPeers = listOf(PEER_ADDRESS_NODE_1)),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    try {
      p2PNetworkImpl1.start()

      p2pNetworkImpl2.start()

      awaitUntilAsserted { assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }
    } finally {
      p2PNetworkImpl1.stop()
      p2pNetworkImpl2.stop()
    }
  }

  @Test
  fun `static peers reconnect`() {
    val p2PNetworkImpl1 =
      P2PNetworkImpl(
        privateKeyBytes = key1,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT1, staticPeers = emptyList()),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    val p2pNetworkImpl2 =
      P2PNetworkImpl(
        privateKeyBytes = key2,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT2, staticPeers = listOf(PEER_ADDRESS_NODE_1)),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    try {
      p2PNetworkImpl1.start()

      p2pNetworkImpl2.start()

      awaitUntilAsserted { assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      p2PNetworkImpl1.dropPeer(PEER_ID_NODE_2, DisconnectReason.TOO_MANY_PEERS)

      awaitUntilAsserted { assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }
    } finally {
      p2PNetworkImpl1.stop()
      p2pNetworkImpl2.stop()
    }
  }

  @Test
  fun `two peers can gossip with each other`() {
    val p2pNetworkImpl1 =
      P2PNetworkImpl(
        privateKeyBytes = key1,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT1, staticPeers = emptyList()),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    val p2pNetworkImpl2 =
      P2PNetworkImpl(
        privateKeyBytes = key2,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT2, staticPeers = listOf(PEER_ADDRESS_NODE_1)),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    try {
      p2pNetworkImpl1.start()

      val blocksReceived = mutableListOf<SealedBeaconBlock>()
      p2pNetworkImpl2.start()
      p2pNetworkImpl2.subscribeToBlocks {
        blocksReceived.add(it)
        SafeFuture.completedFuture(ValidationResult.Companion.Valid)
      }

      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      val randomBlockMessage1 = DataGenerators.randomBlockMessage()
      p2pNetworkImpl1.broadcastMessage(randomBlockMessage1).get()
      val randomBlockMessage2 = DataGenerators.randomBlockMessage(2UL)
      p2pNetworkImpl1.broadcastMessage(randomBlockMessage2).get()

      awaitUntilAsserted {
        assertThat(blocksReceived).hasSameElementsAs(listOf(randomBlockMessage1.payload, randomBlockMessage2.payload))
      }
    } finally {
      p2pNetworkImpl1.stop()
      p2pNetworkImpl2.stop()
    }
  }

  @Test
  fun `peer receiving gossip passes message on`() {
    val p2PNetworkImpl1 =
      P2PNetworkImpl(
        privateKeyBytes = key1,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT1, staticPeers = emptyList()),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    val p2PNetworkImpl2 =
      P2PNetworkImpl(
        privateKeyBytes = key2,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT2, staticPeers = listOf(PEER_ADDRESS_NODE_1, PEER_ADDRESS_NODE_3)),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    val p2PNetworkImpl3 =
      P2PNetworkImpl(
        privateKeyBytes = key3,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT3, staticPeers = emptyList()),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    try {
      p2PNetworkImpl1.start()

      p2PNetworkImpl2.start()
      p2PNetworkImpl2.subscribeToBlocks { SafeFuture.completedFuture(ValidationResult.Companion.Valid) }

      val blockReceived = SafeFuture<SealedBeaconBlock>()
      p2PNetworkImpl3.start()
      p2PNetworkImpl3.subscribeToBlocks {
        blockReceived.complete(it)
        SafeFuture.completedFuture(ValidationResult.Companion.Valid)
      }

      awaitUntilAsserted { assertNetworkIsConnectedToPeer(p2PNetworkImpl1, PEER_ID_NODE_2) }
      awaitUntilAsserted { assertNetworkIsConnectedToPeer(p2PNetworkImpl3, PEER_ID_NODE_2) }

      assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 1)
      assertNetworkHasPeers(network = p2PNetworkImpl2, peers = 2)
      assertNetworkHasPeers(network = p2PNetworkImpl3, peers = 1)

      sleep(1100L) // to make sure that the peers have communicated that they have subscribed to the topic
      // This sleep can be decreased if the heartbeat is decreased (set to 1s for now, see P2PNetworkFactory) in the GossipRouter

      val randomBlockMessage = DataGenerators.randomBlockMessage()
      p2PNetworkImpl1.broadcastMessage(randomBlockMessage)

      assertThat(
        blockReceived.get(100, TimeUnit.MILLISECONDS),
      ).isEqualTo(randomBlockMessage.payload)
    } finally {
      p2PNetworkImpl1.stop()
      p2PNetworkImpl2.stop()
      p2PNetworkImpl3.stop()
    }
  }

  @Test
  fun `peer can send a request`() {
    val p2PNetworkImpl1 =
      P2PNetworkImpl(
        privateKeyBytes = key1,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT1, staticPeers = emptyList()),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    val p2pManagerImpl2 =
      P2PNetworkImpl(
        privateKeyBytes = key2,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT2, staticPeers = listOf(PEER_ADDRESS_NODE_1)),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    try {
      p2PNetworkImpl1.start()

      p2pManagerImpl2.start()

      awaitUntilAsserted { assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pManagerImpl2, peers = 1) }

      val latestBeaconBlockHeader = beaconChain.getLatestBeaconState().latestBeaconBlockHeader
      val expectedStatus =
        Status(
          forkIdHash = forkIdHashProvider.currentForkIdHash(),
          latestStateRoot = latestBeaconBlockHeader.hash,
          latestBlockNumber = latestBeaconBlockHeader.number,
        )
      val peer1 =
        p2pManagerImpl2.peerLookup.getPeer(LibP2PNodeId(PeerId.fromBase58(PEER_ID_NODE_1)))
          ?: throw IllegalStateException("Peer with ID $PEER_ID_NODE_1 not found in p2pManagerImpl2")
      val maruPeer1 = DefaultMaruPeer(peer1, rpcMethods, statusMessageFactory)

      val responseFuture = maruPeer1.sendStatus()

      assertThatNoException().isThrownBy { responseFuture.get(500L, TimeUnit.MILLISECONDS) }
      assertThat(
        responseFuture.get(500L, TimeUnit.MILLISECONDS),
      ).isEqualTo(expectedStatus)
    } finally {
      p2PNetworkImpl1.stop()
      p2pManagerImpl2.stop()
    }
  }

  @Test
  fun `peer can send beacon blocks by range request`() {
    // Set up beacon chain with some blocks
    val testBeaconChain = InMemoryBeaconChain(DataGenerators.randomBeaconState(number = 0u, timestamp = 0u))
    val storedBlocks =
      (0UL..10UL).map { blockNumber ->
        DataGenerators.randomSealedBeaconBlock(number = blockNumber)
      }

    testBeaconChain.newUpdater().use { updater ->
      storedBlocks.forEach { block ->
        updater.putSealedBeaconBlock(block)
      }
      updater.commit()
    }

    val p2PNetworkImpl1 =
      P2PNetworkImpl(
        privateKeyBytes = key1,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT1, staticPeers = emptyList()),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = testBeaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    val p2pManagerImpl2 =
      P2PNetworkImpl(
        privateKeyBytes = key2,
        p2pConfig = P2P(ipAddress = IPV4, port = PORT2, staticPeers = listOf(PEER_ADDRESS_NODE_1)),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
      )
    try {
      p2PNetworkImpl1.start()
      p2pManagerImpl2.start()

      awaitUntilAsserted { assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pManagerImpl2, peers = 1) }

      val peer1 =
        p2pManagerImpl2.peerLookup.getPeer(LibP2PNodeId(PeerId.fromBase58(PEER_ID_NODE_1)))
          ?: throw IllegalStateException("Peer with ID $PEER_ID_NODE_1 not found in p2pManagerImpl2")
      val maruPeer1 = DefaultMaruPeer(peer1, rpcMethods, statusMessageFactory)

      val startBlockNumber = 3UL
      val count = 5UL
      val responseFuture = maruPeer1.sendBeaconBlocksByRange(startBlockNumber, count)

      val response = responseFuture.get(5, TimeUnit.SECONDS)

      val expectedBlocks = storedBlocks.subList(startBlockNumber.toInt(), startBlockNumber.toInt() + count.toInt())
      assertThat(response.blocks).hasSize(5)
      assertThat(response.blocks).isEqualTo(expectedBlocks)
    } finally {
      p2PNetworkImpl1.stop()
      p2pManagerImpl2.stop()
    }
  }

  private fun assertNetworkHasPeers(
    network: P2PNetworkImpl,
    peers: Int,
  ) {
    assertThat(network.peerCount).isEqualTo(peers)
  }

  private fun awaitUntilAsserted(
    timeout: Long = 6000L,
    timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    condition: () -> Unit,
  ) {
    await()
      .timeout(timeout, timeUnit)
      .untilAsserted(condition)
  }

  private fun assertNetworkIsConnectedToPeer(
    p2pNetwork3: P2PNetworkImpl,
    peer: String,
  ) {
    assertThat(
      p2pNetwork3.isConnected(peer),
    ).isTrue()
  }
}
