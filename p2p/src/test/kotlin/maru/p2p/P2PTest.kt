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
import io.libp2p.etc.types.fromHex
import java.lang.Thread.sleep
import java.net.ServerSocket
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import maru.config.P2PConfig
import maru.consensus.ForkIdManagerFactory.createForkIdHashManager
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.SealedBeaconBlock
import maru.core.ext.DataGenerators
import maru.core.ext.metrics.TestMetrics
import maru.database.BeaconChain
import maru.database.InMemoryBeaconChain
import maru.database.InMemoryP2PState
import maru.p2p.fork.ForkPeeringManager
import maru.p2p.messages.Status
import maru.p2p.messages.StatusManager
import maru.p2p.topics.MessageDataSerDe
import maru.serialization.rlp.RLPSerializers
import maru.syncing.CLSyncStatus
import maru.syncing.ELSyncStatus
import maru.syncing.SyncStatusProvider
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatNoException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.hyperledger.besu.consensus.qbft.core.types.QbftMessage
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.time.SystemTimeProvider
import tech.pegasys.teku.networking.eth2.rpc.core.RpcException
import tech.pegasys.teku.networking.eth2.rpc.core.RpcResponseStatus
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNodeId
import tech.pegasys.teku.networking.p2p.libp2p.MultiaddrPeerAddress
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.NodeId
import maru.p2p.ext.DataGenerators as P2P2DataGenerators

@Execution(ExecutionMode.SAME_THREAD)
class P2PTest {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  private var port1: UInt = 0u
  private var port2: UInt = 0u
  private var port3: UInt = 0u
  private var port4: UInt = 0u
  private var port5: UInt = 0u
  private var port6: UInt = 0u

  companion object {
    private val chainId = 1337u

    private const val IPV4: String = "127.0.0.1"

    private const val PEER_ID_NODE_1: String = "16Uiu2HAmPRfinavM2jE9BSkCagBGStJ2SEkPPm6fxFVMdCQebzt6"
    private const val PEER_ID_NODE_2: String = "16Uiu2HAmVXtqhevTAJqZucPbR2W4nCMpetrQASgjZpcxDEDaUPPt"
    private const val PEER_ID_NODE_3: String = "16Uiu2HAkzq767a82zfyUz4VLgPbFrxSQBrdmUYxgNDbwgvmjwWo5"

    @JvmStatic
    fun p2pPorts(): Stream<Arguments> =
      Stream.of(
        Arguments.of(findFreePort().toInt(), findFreePort().toInt(), findFreePort().toInt()),
        Arguments.of(0, 0, 0),
      )

    private fun findFreePort(): UInt =
      runCatching {
        ServerSocket(0).use { socket ->
          socket.reuseAddress = true
          socket.localPort.toUInt()
        }
      }.getOrElse {
        throw IllegalStateException("Could not find a free port", it)
      }

    private fun createPeerAddress(
      port: UInt,
      peerId: String,
    ): String = "/ip4/$IPV4/tcp/$port/p2p/$peerId"

    private val key1 = "0802122012c0b113e2b0c37388e2b484112e13f05c92c4471e3ee1dfaa368fa5045325b2".fromHex()
    private val key2 = "0802122100f3d2fffa99dc8906823866d96316492ebf7a8478713a89a58b7385af85b088a1".fromHex()
    private val key3 = "080212204437acb8e84bc346f7640f239da84abe99bc6f97b7855f204e34688d2977fd57".fromHex()
    private val p2PState = InMemoryP2PState()

    private fun getSyncStatusProvider(): SyncStatusProvider =
      object : SyncStatusProvider {
        override fun getCLSyncStatus(): CLSyncStatus = CLSyncStatus.SYNCED

        override fun getElSyncStatus(): ELSyncStatus = ELSyncStatus.SYNCED

        override fun onClSyncStatusUpdate(handler: (newStatus: CLSyncStatus) -> Unit) {}

        override fun onElSyncStatusUpdate(handler: (newStatus: ELSyncStatus) -> Unit) {}

        override fun isBeaconChainSynced(): Boolean = true

        override fun isELSynced(): Boolean = true

        override fun onBeaconSyncComplete(handler: () -> Unit) {}

        override fun onFullSyncComplete(handler: () -> Unit) {}

        override fun getBeaconSyncDistance(): ULong = 10UL

        override fun getCLSyncTarget(): ULong = 100UL
      }

    private val beaconChain: InMemoryBeaconChain = InMemoryBeaconChain.fromGenesis()
    private val forkIdHashManager: ForkPeeringManager =
      createForkIdHashManager(
        chainId = chainId,
        beaconChain = beaconChain,
      )
    private val statusManager: StatusManager = StatusManager(beaconChain, forkIdHashManager)
    private val rpcMethods = createRpcMethods()

    fun createRpcMethods(): RpcMethods {
      val rpcProtocolIdGenerator = LineaRpcProtocolIdGenerator(chainId)
      lateinit var maruPeerManager: MaruPeerManager
      val rpcMethods =
        RpcMethods(
          statusManager = statusManager,
          lineaRpcProtocolIdGenerator = rpcProtocolIdGenerator,
          peerLookup = { maruPeerManager },
          beaconChain = beaconChain,
        )
      val reputationManager =
        MaruReputationManager(
          metricsSystem = NoOpMetricsSystem(),
          timeProvider = SystemTimeProvider(),
          isStaticPeer = { _: NodeId -> true },
          reputationConfig = P2PConfig.Reputation(),
        )
      val maruPeerFactory =
        DefaultMaruPeerFactory(
          rpcMethods = rpcMethods,
          statusManager = statusManager,
          p2pConfig = P2PConfig(ipAddress = IPV4, port = findFreePort()),
        )

      val syncStatusProvider = getSyncStatusProvider()
      val syncStatusProviderProvider = { syncStatusProvider }

      maruPeerManager =
        MaruPeerManager(
          maruPeerFactory = maruPeerFactory,
          p2pConfig = P2PConfig(ipAddress = IPV4, port = findFreePort()),
          reputationManager = reputationManager,
          isStaticPeer = { false },
          syncStatusProviderProvider = syncStatusProviderProvider,
        )
      return rpcMethods
    }

    private fun createP2PNetwork(
      privateKey: ByteArray,
      port: UInt,
      staticPeers: List<String> = emptyList(),
      beaconChain: BeaconChain = Companion.beaconChain,
      reconnectDelay: Duration = 1.seconds,
      statusManager: StatusManager = Companion.statusManager,
      statusUpdate: P2PConfig.StatusUpdate = P2PConfig.StatusUpdate(),
      discovery: P2PConfig.Discovery? = null,
      reputationConfig: P2PConfig.Reputation = P2PConfig.Reputation(),
    ): P2PNetworkImpl =
      P2PNetworkImpl(
        privateKeyBytes = privateKey,
        p2pConfig =
          P2PConfig(
            ipAddress = IPV4,
            port = port,
            staticPeers = staticPeers,
            reconnectDelay = reconnectDelay,
            statusUpdate = statusUpdate,
            discovery = discovery,
            reputation = reputationConfig,
          ),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockCompressorSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusManager = statusManager,
        beaconChain = beaconChain,
        metricsSystem = NoOpMetricsSystem(),
        forkIdHashManager = forkIdHashManager,
        isBlockImportEnabledProvider = { true },
        p2PState = p2PState,
        syncStatusProviderProvider = { getSyncStatusProvider() },
      )
  }

  @BeforeEach
  fun setUp() {
    port1 = findFreePort()
    port2 = findFreePort()
    port3 = findFreePort()
    port4 = findFreePort()
    port5 = findFreePort()
    port6 = findFreePort()
  }

  @Test
  fun `static peer can be added`() {
    val p2pNetworkImpl1 = createP2PNetwork(privateKey = key1, port = port1)
    val p2pNetworkImpl2 = createP2PNetwork(privateKey = key2, port = port2)
    try {
      p2pNetworkImpl1.start().get()

      p2pNetworkImpl2.start().get()

      p2pNetworkImpl1.addStaticPeer(
        peerAddress = MultiaddrPeerAddress.fromAddress(createPeerAddress(port2, PEER_ID_NODE_2)),
      )

      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2.stop().get()
    }
  }

  @Test
  fun `static peers can be removed`() {
    val p2pNetworkImpl1 = createP2PNetwork(privateKey = key1, port = port1)
    val p2pNetworkImpl2 = createP2PNetwork(privateKey = key2, port = port2)

    try {
      p2pNetworkImpl1.start().get()
      p2pNetworkImpl2.start().get()

      val peerAddress2 = createPeerAddress(port2, PEER_ID_NODE_2)
      p2pNetworkImpl1.addStaticPeer(peerAddress = MultiaddrPeerAddress.fromAddress(peerAddress2))

      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      p2pNetworkImpl1.removeStaticPeer(peerAddress = MultiaddrPeerAddress.fromAddress(peerAddress2))

      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 0) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 0) }
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2.stop().get()
    }
  }

  @Test
  fun `static peers can be configured`() {
    val p2pNetworkImpl1 = createP2PNetwork(privateKey = key1, port = port1)
    val p2pNetworkImpl2 =
      createP2PNetwork(privateKey = key2, port = port2, staticPeers = listOf(createPeerAddress(port1, PEER_ID_NODE_1)))
    try {
      p2pNetworkImpl1.start().get()

      p2pNetworkImpl2.start().get()

      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2.stop().get()
    }
  }

  @Test
  fun `static peers reconnect`() {
    val p2pNetworkImpl1 =
      createP2PNetwork(
        privateKey = key1,
        port = port1,
        reputationConfig = P2PConfig.Reputation(cooldownPeriod = 1.seconds),
      )
    val p2pNetworkImpl2 =
      createP2PNetwork(
        privateKey = key2,
        port = port2,
        staticPeers = listOf(createPeerAddress(port1, PEER_ID_NODE_1)),
        reconnectDelay = 2.seconds,
      )

    try {
      p2pNetworkImpl1.start().get()

      p2pNetworkImpl2.start().get()

      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      p2pNetworkImpl1.dropPeer(peer = PEER_ID_NODE_2, reason = DisconnectReason.TOO_MANY_PEERS)

      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2.stop().get()
    }
  }

  @Test
  fun `two peers can gossip blocks with each other`() {
    val beaconChain2 = InMemoryBeaconChain(DataGenerators.randomBeaconState(number = 0u, timestamp = 0u))
    val p2pNetworkImpl1 = createP2PNetwork(privateKey = key1, port = port1)
    val p2pNetworkImpl2 =
      createP2PNetwork(
        privateKey = key2,
        port = port2,
        staticPeers = listOf(createPeerAddress(port1, PEER_ID_NODE_1)),
        beaconChain = beaconChain2,
      )
    try {
      p2pNetworkImpl1.start().get()

      val blocksReceived = mutableListOf<SealedBeaconBlock>()
      p2pNetworkImpl2.start().get()
      p2pNetworkImpl2.subscribeToBlocks {
        updateBeaconChainState(beaconChain2, it.beaconBlock.beaconBlockHeader)
        blocksReceived.add(it)
        SafeFuture.completedFuture(ValidationResult.Companion.Valid)
      }

      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      val randomBlockMessage1 = P2P2DataGenerators.randomBlockMessage()
      p2pNetworkImpl1.broadcastMessage(message = randomBlockMessage1).get()
      val randomBlockMessage2 = P2P2DataGenerators.randomBlockMessage(blockNumber = 2UL)
      p2pNetworkImpl1.broadcastMessage(message = randomBlockMessage2).get()

      awaitUntilAsserted {
        assertThat(blocksReceived).hasSameElementsAs(listOf(randomBlockMessage1.payload, randomBlockMessage2.payload))
      }
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2.stop().get()
    }
  }

  @Test
  fun `two peers can gossip QBFT messages with each other`() {
    val p2pNetworkImpl1 = createP2PNetwork(privateKey = key1, port = port1)
    val p2pNetworkImpl2 =
      createP2PNetwork(
        privateKey = key2,
        port = port2,
        staticPeers = listOf(createPeerAddress(port1, PEER_ID_NODE_1)),
      )
    try {
      p2pNetworkImpl1.start()

      val qbftMessagesReceived = mutableListOf<QbftMessage>()
      p2pNetworkImpl2.start()
      p2pNetworkImpl2.subscribeToQbftMessages {
        qbftMessagesReceived.add(it)
        SafeFuture.completedFuture(ValidationResult.Companion.Valid)
      }

      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      // Create test QBFT messages
      val testMessage1 = createTestQbftMessage(code = 42, data = "test data 1".toByteArray())
      val testMessage2 = createTestQbftMessage(code = 43, data = "test data 2".toByteArray())

      val qbftMessage1 = MessageData(GossipMessageType.QBFT, Version.V1, testMessage1)
      val qbftMessage2 = MessageData(GossipMessageType.QBFT, Version.V1, testMessage2)

      p2pNetworkImpl1.broadcastMessage(qbftMessage1).get()
      p2pNetworkImpl1.broadcastMessage(qbftMessage2).get()

      awaitUntilAsserted {
        assertThat(qbftMessagesReceived).hasSize(2)
        assertThat(qbftMessagesReceived[0].data.code).isEqualTo(42)
        assertThat(qbftMessagesReceived[1].data.code).isEqualTo(43)
      }
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2.stop().get()
    }
  }

  @Test
  fun `peer receiving block gossip passes message on`() {
    val beaconChain2 = InMemoryBeaconChain(DataGenerators.randomBeaconState(number = 0u, timestamp = 0u))
    val beaconChain3 = InMemoryBeaconChain(DataGenerators.randomBeaconState(number = 0u, timestamp = 0u))
    val p2pNetworkImpl1 = createP2PNetwork(privateKey = key1, port = port1, staticPeers = emptyList())
    val p2pNetworkImpl2 =
      createP2PNetwork(
        privateKey = key2,
        port = port2,
        staticPeers = listOf(createPeerAddress(port1, PEER_ID_NODE_1), createPeerAddress(port3, PEER_ID_NODE_3)),
        beaconChain = beaconChain2,
      )
    val p2pNetworkImpl3 =
      createP2PNetwork(privateKey = key3, port = port3, staticPeers = emptyList(), beaconChain = beaconChain3)
    try {
      p2pNetworkImpl1.start().get()
      p2pNetworkImpl2.start().get()
      p2pNetworkImpl2.subscribeToBlocks {
        updateBeaconChainState(beaconChain = beaconChain2, beaconBlockHeader = it.beaconBlock.beaconBlockHeader)
        SafeFuture.completedFuture(ValidationResult.Companion.Valid)
      }

      val blockReceived = SafeFuture<SealedBeaconBlock>()
      p2pNetworkImpl3.start().get()
      p2pNetworkImpl3.subscribeToBlocks {
        updateBeaconChainState(beaconChain3, it.beaconBlock.beaconBlockHeader)
        blockReceived.complete(it)
        SafeFuture.completedFuture(ValidationResult.Companion.Valid)
      }

      awaitUntilAsserted { assertNetworkIsConnectedToPeer(p2pNetworkImpl1, PEER_ID_NODE_2) }
      awaitUntilAsserted { assertNetworkIsConnectedToPeer(p2pNetworkImpl3, PEER_ID_NODE_2) }

      assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 1)
      assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 2)
      assertNetworkHasPeers(network = p2pNetworkImpl3, peers = 1)

      sleep(1100L) // to make sure that the peers have communicated that they have subscribed to the topic
      // This sleep can be decreased if the heartbeat is decreased (set to 1s for now, see P2PNetworkFactory) in the GossipRouter

      val randomBlockMessage = P2P2DataGenerators.randomBlockMessage()
      p2pNetworkImpl1.broadcastMessage(message = randomBlockMessage)

      assertThat(
        blockReceived.get(200, TimeUnit.MILLISECONDS),
      ).isEqualTo(randomBlockMessage.payload)
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2.stop().get()
      p2pNetworkImpl3.stop().get()
    }
  }

  private fun updateBeaconChainState(
    beaconChain: BeaconChain,
    beaconBlockHeader: BeaconBlockHeader,
  ) {
    beaconChain
      .newBeaconChainUpdater()
      .putBeaconState(
        beaconState =
          beaconChain.getLatestBeaconState().copy(
            beaconBlockHeader = beaconBlockHeader,
          ),
      ).commit()
  }

  @Test
  fun `peer can send a status request`() {
    val p2pNetworkImpl1 = createP2PNetwork(privateKey = key1, port = port1)
    val p2pNetworkImpl2 =
      createP2PNetwork(privateKey = key2, port = port2, staticPeers = listOf(createPeerAddress(port1, PEER_ID_NODE_1)))
    try {
      p2pNetworkImpl1.start().get()
      p2pNetworkImpl2.start().get()

      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      val latestBeaconBlockHeader = beaconChain.getLatestBeaconState().beaconBlockHeader
      val expectedStatus =
        Status(
          forkIdHash = forkIdHashManager.currentForkHash(),
          latestStateRoot = latestBeaconBlockHeader.hash,
          latestBlockNumber = latestBeaconBlockHeader.number,
        )
      val peer1 =
        p2pNetworkImpl2.getPeerLookup().getPeer(nodeId = LibP2PNodeId(PeerId.fromBase58(PEER_ID_NODE_1)))
          ?: throw IllegalStateException("Peer with ID $PEER_ID_NODE_1 not found in p2pNetworkImpl2")
      val maruPeer1 =
        DefaultMaruPeer(
          delegatePeer = peer1,
          rpcMethods = rpcMethods,
          statusManager = statusManager,
          p2pConfig = P2PConfig(ipAddress = IPV4, port = port1),
        )

      val responseFuture = maruPeer1.sendStatus()

      assertThatNoException().isThrownBy { responseFuture.get(500L, TimeUnit.MILLISECONDS) }
      assertThat(peer1.getStatus()).isEqualTo(expectedStatus)
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2.stop().get()
    }
  }

  @Test
  fun `peer can send beacon blocks by range request`() {
    // Set up beacon chain with some blocks
    val testBeaconChain =
      InMemoryBeaconChain(initialBeaconState = DataGenerators.randomBeaconState(number = 0u, timestamp = 0u))
    val storedBlocks =
      (0UL..10UL).map { blockNumber ->
        DataGenerators.randomSealedBeaconBlock(number = blockNumber)
      }

    testBeaconChain.newBeaconChainUpdater().use { updater ->
      storedBlocks.forEach { block ->
        updater.putSealedBeaconBlock(block)
      }
      updater.putBeaconState(
        BeaconState(
          beaconBlockHeader = storedBlocks.last().beaconBlock.beaconBlockHeader,
          validators = DataGenerators.randomValidators(),
        ),
      )
      updater.commit()
    }
    val p2pNetworkImpl1 = createP2PNetwork(privateKey = key1, port = port1, beaconChain = testBeaconChain)
    val p2pNetworkImpl2 =
      createP2PNetwork(privateKey = key2, port = port2, staticPeers = listOf(createPeerAddress(port1, PEER_ID_NODE_1)))

    try {
      p2pNetworkImpl1.start().get()
      p2pNetworkImpl2.start().get()

      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      val peer1 =
        p2pNetworkImpl2.getPeerLookup().getPeer(LibP2PNodeId(PeerId.fromBase58(PEER_ID_NODE_1)))
          ?: throw IllegalStateException("Peer with ID $PEER_ID_NODE_1 not found in p2pNetworkImpl2")

      val startBlockNumber = 3UL
      val count = 5UL
      val responseFuture = peer1.sendBeaconBlocksByRange(startBlockNumber = startBlockNumber, count = count)

      val response = responseFuture.get(5, TimeUnit.SECONDS)

      val expectedBlocks =
        storedBlocks.subList(
          fromIndex = startBlockNumber.toInt(),
          toIndex =
            startBlockNumber.toInt() + count.toInt(),
        )
      assertThat(response.blocks).hasSize(5)
      assertThat(response.blocks).isEqualTo(expectedBlocks)
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2.stop().get()
    }
  }

  @Test
  fun `peer send a beacon blocks by range request and receive exception when callee throws error`() {
    val p2pNetworkImpl1 = createP2PNetwork(privateKey = key1, port = port1, beaconChain = getMockedBeaconChain())
    val p2pNetworkImpl2 =
      createP2PNetwork(privateKey = key2, port = port2, staticPeers = listOf(createPeerAddress(port1, PEER_ID_NODE_1)))
    try {
      p2pNetworkImpl1.start().get()
      p2pNetworkImpl2.start().get()

      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      val peer1 =
        p2pNetworkImpl2.getPeerLookup().getPeer(nodeId = LibP2PNodeId(PeerId.fromBase58(PEER_ID_NODE_1)))
          ?: throw IllegalStateException("Peer with ID $PEER_ID_NODE_1 not found in p2pNetworkImpl2")

      val startBlockNumber = 3UL
      val count = 5UL
      val responseFuture = peer1.sendBeaconBlocksByRange(startBlockNumber = startBlockNumber, count = count)

      assertThatThrownBy { responseFuture.get() }
        .isInstanceOf(ExecutionException::class.java)
        .hasCauseInstanceOf(RpcException::class.java)
        .hasMessageContaining("Missing sealed beacon block")
        .matches { (it.cause as RpcException).responseCode == RpcResponseStatus.RESOURCE_UNAVAILABLE }
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2.stop().get()
    }
  }

  @Test
  fun `should expose discovery enr properly - discovery disabled`() {
    val p2pNetworkImpl1 =
      createP2PNetwork(
        privateKey = key1,
        port = port1,
        discovery = null,
      )
    try {
      p2pNetworkImpl1.start().get()
      val enr =
        ENR.factory.fromEnr(
          p2pNetworkImpl1
            .nodeRecords()
            .first()
            .asEnr(),
        )
      assertThat(enr.tcpAddress.get().port).isEqualTo(port1.toInt())
      assertThat(enr.udpAddress.get().port).isEqualTo(port1.toInt())
      assertThat(
        enr.udpAddress
          .get()
          .address
          .toString(),
      ).doesNotContain("0.0.0.0")
    } finally {
      p2pNetworkImpl1.stop().get()
    }
  }

  @Test
  fun `should expose discovery enr properly - discovery enabled`() {
    val p2pNetworkImpl1 =
      createP2PNetwork(
        privateKey = key1,
        port = port1,
        discovery =
          P2PConfig.Discovery(
            port = port2,
            refreshInterval = 1.seconds,
          ),
      )
    try {
      p2pNetworkImpl1.start().get()
      val enr = ENR.factory.fromEnr(p2pNetworkImpl1.enr)
      assertThat(enr.tcpAddress.get().port).isEqualTo(port1.toInt())
      assertThat(enr.udpAddress.get().port).isEqualTo(port2.toInt())
      assertThat(
        enr.udpAddress
          .get()
          .address
          .toString(),
      ).doesNotContain("0.0.0.0")
    } finally {
      p2pNetworkImpl1.stop().get()
    }
  }

  @ParameterizedTest
  @MethodSource("p2pPorts")
  fun `peer can be discovered and disconnected peers can be rediscovered`(
    p2pPort1: Int,
    p2pPort2: Int,
    p2pPort3: Int,
  ) {
    val refreshInterval = 5.seconds
    val p2pNetworkImpl1 =
      createP2PNetwork(
        privateKey = key1,
        port = port1,
        discovery =
          P2PConfig.Discovery(
            port = port4,
            refreshInterval = refreshInterval,
          ),
        reputationConfig =
          P2PConfig.Reputation(
            cooldownPeriod = 1.seconds,
            banPeriod = 2.seconds,
          ),
      )

    var p2pNetworkImpl2: P2PNetworkImpl? = null
    var p2pNetworkImpl3: P2PNetworkImpl? = null
    try {
      p2pNetworkImpl1.start().get()

      p2pNetworkImpl2 =
        createP2PNetwork(
          privateKey = key2,
          port = port2,
          beaconChain =
            InMemoryBeaconChain(
              initialBeaconState = DataGenerators.randomBeaconState(number = 0u, timestamp = 0u),
            ),
          discovery =
            P2PConfig.Discovery(
              port = port5,
              bootnodes = listOf(p2pNetworkImpl1.enr!!),
              refreshInterval = refreshInterval,
            ),
          reputationConfig =
            P2PConfig.Reputation(
              cooldownPeriod = 1.seconds,
              banPeriod = 2.minutes,
            ),
        )

      p2pNetworkImpl3 =
        createP2PNetwork(
          privateKey = key3,
          port = port3,
          beaconChain =
            InMemoryBeaconChain(
              initialBeaconState = DataGenerators.randomBeaconState(number = 0u, timestamp = 0u),
            ),
          discovery =
            P2PConfig.Discovery(
              port = port6,
              bootnodes = listOf(p2pNetworkImpl1.enr!!),
              refreshInterval = refreshInterval,
            ),
          reputationConfig =
            P2PConfig.Reputation(
              cooldownPeriod = 1.seconds,
              banPeriod = 2.minutes,
            ),
        )

      p2pNetworkImpl2.start().get()
      p2pNetworkImpl3.start().get()

      val awaitTimeoutInSeconds = 30L
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl1, PEER_ID_NODE_2)
      }
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl2, PEER_ID_NODE_1)
      }
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl3, PEER_ID_NODE_2)
      }
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl1, PEER_ID_NODE_3)
      }
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl2, PEER_ID_NODE_3)
      }
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl3, PEER_ID_NODE_1)
      }

      p2pNetworkImpl2.dropPeer(peer = PEER_ID_NODE_1, reason = DisconnectReason.TOO_MANY_PEERS).get()
      p2pNetworkImpl2.dropPeer(peer = PEER_ID_NODE_3, reason = DisconnectReason.TOO_MANY_PEERS).get()

      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl1, PEER_ID_NODE_2)
      }
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl2, PEER_ID_NODE_1)
      }
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl3, PEER_ID_NODE_2)
      }
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl1, PEER_ID_NODE_3)
      }
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl2, PEER_ID_NODE_3)
      }
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl3, PEER_ID_NODE_1)
      }
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2?.stop()?.get()
      p2pNetworkImpl3?.stop()?.get()
    }
  }

  @Test
  fun `sending status updates updates status`() {
    val refreshInterval = 5.seconds
    val p2pNetworkImpl1 =
      createP2PNetwork(
        privateKey = key1,
        port = port1,
        statusUpdate =
          P2PConfig.StatusUpdate(
            refreshInterval = 1.seconds,
            refreshIntervalLeeway = 1.seconds,
            timeout = 1.seconds,
          ),
        statusManager = getMockedStatusMessageFactory(),
      )

    val p2pNetworkImpl2 =
      createP2PNetwork(
        privateKey = key2,
        port = port2,
        beaconChain =
          InMemoryBeaconChain(
            initialBeaconState = DataGenerators.randomBeaconState(number = 0u, timestamp = 0u),
          ),
        staticPeers = listOf(createPeerAddress(port1, PEER_ID_NODE_1)),
        statusUpdate =
          P2PConfig.StatusUpdate(
            refreshInterval = 1.seconds,
            refreshIntervalLeeway = 1.seconds,
            timeout = 1.seconds,
          ),
        statusManager = getMockedStatusMessageFactory(),
      )

    try {
      p2pNetworkImpl1.start().get()
      p2pNetworkImpl2.start().get()

      val awaitTimeoutInSeconds = 30L
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl1, PEER_ID_NODE_2)
      }
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl2, PEER_ID_NODE_1)
      }

      val peer1 = p2pNetworkImpl1.maruPeerManager.getPeers()[0]
      val peer2 = p2pNetworkImpl2.maruPeerManager.getPeers()[0]

      val startTime = System.currentTimeMillis()
      var currentBlockNumber = 0uL
      while (System.currentTimeMillis() < startTime + 13000L) { // max 13 seconds to run this test
        assertNetworkIsConnectedToPeer(p2pNetworkImpl1, PEER_ID_NODE_2)
        assertNetworkIsConnectedToPeer(p2pNetworkImpl2, PEER_ID_NODE_1)
        awaitUntilAsserted { peer1.getStatus()!!.latestBlockNumber == currentBlockNumber }
        awaitUntilAsserted { peer2.getStatus()!!.latestBlockNumber == currentBlockNumber }
        if (peer1.getStatus()!!.latestBlockNumber == 5uL) {
          // we have reached the end of the blocks, so we can stop
          break
        }
        currentBlockNumber++
      }
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2.stop().get()
    }
  }

  @Test
  fun `peer sending status update too late is disconnected`() {
    val p2pNetworkImpl1 =
      createP2PNetwork(
        privateKey = key1,
        port = port1,
        statusUpdate =
          P2PConfig.StatusUpdate(
            refreshInterval = 1.seconds,
            refreshIntervalLeeway = 0.seconds,
            timeout = 1.seconds,
          ),
        reputationConfig =
          P2PConfig.Reputation(
            cooldownPeriod = 50.milliseconds,
          ),
      )

    // Node 2 is initiating the connection and is only sending status updates after 2 seconds.
    // It should be disconnected by node 1, which expects a status update within 1 second.
    // The initial status update works because node 1 has a timeout of 1 second for the status update.
    val p2pNetworkImpl2 =
      createP2PNetwork(
        privateKey = key2,
        port = port2,
        staticPeers = listOf(createPeerAddress(port1, PEER_ID_NODE_1)),
        beaconChain =
          InMemoryBeaconChain(
            initialBeaconState = DataGenerators.randomBeaconState(number = 0u, timestamp = 0u),
          ),
        reconnectDelay = 100.milliseconds,
        statusUpdate =
          P2PConfig.StatusUpdate(
            refreshInterval = 2.seconds,
            refreshIntervalLeeway = 1.seconds,
            timeout = 1.seconds,
          ),
      )

    try {
      p2pNetworkImpl1.start().get()
      p2pNetworkImpl2.start().get()

      val awaitTimeoutInSeconds = 30L
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl1, PEER_ID_NODE_2)
      }
      awaitUntilAsserted(timeout = awaitTimeoutInSeconds, timeUnit = TimeUnit.SECONDS) {
        assertNetworkIsConnectedToPeer(p2pNetworkImpl2, PEER_ID_NODE_1)
      }

      // Check for up to 6 seconds that the peers are at least disconnected twice.
      // Node 2 is reconnecting because of the static connection
      val endTime = System.currentTimeMillis() + 6000L
      var disconnectCount = 0
      while ((System.currentTimeMillis() < endTime) && disconnectCount < 2) {
        sleep(50L)
        if (p2pNetworkImpl1.getPeer(peerId = PEER_ID_NODE_2) == null) {
          disconnectCount++
          do {
            // wait for the peer to be connected again
            sleep(50L)
          } while (p2pNetworkImpl1.getPeer(peerId = PEER_ID_NODE_2) == null && (System.currentTimeMillis() < endTime))
        }
      }

      assertThat(disconnectCount).isGreaterThanOrEqualTo(2)
    } finally {
      p2pNetworkImpl1.stop().get()
      p2pNetworkImpl2.stop().get()
    }
  }

  private fun assertNetworkHasPeers(
    network: P2PNetworkImpl,
    peers: Int,
  ) {
    assertThat(network.peerCount).isEqualTo(peers)
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

  private fun assertNetworkIsConnectedToPeer(
    p2pNetwork: P2PNetworkImpl,
    peer: String,
  ) {
    assertThat(
      p2pNetwork.isConnected(peer),
    ).isTrue()
  }

  private fun getMockedStatusMessageFactory(): StatusManager {
    val beaconChain = mock<BeaconChain>()
    val beaconState = mock<BeaconState>()
    val beaconBlockHeader = mock<BeaconBlockHeader>()
    whenever(beaconChain.getLatestBeaconState()).thenReturn(beaconState)
    whenever(beaconState.beaconBlockHeader).thenReturn(beaconBlockHeader)
    whenever(beaconBlockHeader.hash).thenReturn(ByteArray(32))
    whenever(beaconBlockHeader.number).thenReturn(0uL, 1uL, 2uL, 3uL, 4uL, 5uL)

    val statusManager = StatusManager(beaconChain, forkIdHashManager)
    return statusManager
  }

  private fun getMockedBeaconChain(): BeaconChain {
    val mockedBeaconChain = mock<BeaconChain>(RETURNS_DEEP_STUBS)
    whenever(mockedBeaconChain.getSealedBeaconBlock(any<ULong>())).thenThrow(
      IllegalStateException("Missing sealed beacon block"),
    )
    return mockedBeaconChain
  }

  private fun createTestQbftMessage(
    code: Int,
    data: ByteArray,
  ): QbftMessage {
    val messageData = MessageDataSerDe.MaruMessageData(code, Bytes.wrap(data))
    return QbftMessage { messageData }
  }
}
