package maru.syncing.beaconchain

import java.util.concurrent.TimeUnit
import maru.config.P2P
import maru.config.consensus.ElFork
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ConsensusConfig
import maru.consensus.ForkIdHashProvider
import maru.consensus.ForkIdHasher
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import maru.core.ext.DataGenerators
import maru.core.ext.metrics.TestMetrics
import maru.crypto.Hashing
import maru.database.BeaconChain
import maru.database.InMemoryBeaconChain
import maru.p2p.P2PNetworkImpl
import maru.p2p.messages.StatusMessageFactory
import maru.serialization.ForkIdSerializers
import maru.serialization.rlp.RLPSerializers
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.mockito.Mockito.mock
import tech.pegasys.teku.networking.p2p.libp2p.MultiaddrPeerAddress

@Execution(ExecutionMode.SAME_THREAD)
class CLSyncServiceImplTest {
  companion object {
    private val chainId = 1337u

    private const val IPV4 = "127.0.0.1"

    private const val PORT1 = 9234u
    private const val PORT2 = 9235u

    private const val PRIVATE_KEY1: String =
      "0x0802122012c0b113e2b0c37388e2b484112e13f05c92c4471e3ee1dfaa368fa5045325b2"
    private const val PRIVATE_KEY2: String =
      "0x0802122100f3d2fffa99dc8906823866d96316492ebf7a8478713a89a58b7385af85b088a1"

    private const val PEER_ID_NODE_1: String = "16Uiu2HAmPRfinavM2jE9BSkCagBGStJ2SEkPPm6fxFVMdCQebzt6"
    private const val PEER_ID_NODE_2: String = "16Uiu2HAmVXtqhevTAJqZucPbR2W4nCMpetrQASgjZpcxDEDaUPPt"

    private const val PEER_ADDRESS_NODE_1: String = "/ip4/$IPV4/tcp/$PORT1/p2p/$PEER_ID_NODE_1"
    private const val PEER_ADDRESS_NODE_2: String = "/ip4/$IPV4/tcp/$PORT2/p2p/$PEER_ID_NODE_2"

    private val key1 = Bytes.fromHexString(PRIVATE_KEY1).toArray()
    private val key2 = Bytes.fromHexString(PRIVATE_KEY2).toArray()

    fun createForkIdHashProvider(beaconChain: BeaconChain): ForkIdHashProvider {
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

  private val genesisState = DataGenerators.randomBeaconState(0u)

  // starting the service
  // stopping the service
  // syncing to a sync target
  // new sync target aborts the previous sync
  // error during sync restarts the sync

  @Test
  fun `sync service downloads and imports blocks from peers`() {
    val beaconChain1 = InMemoryBeaconChain(genesisState)
    val forkIdHashProvider1 = createForkIdHashProvider(beaconChain1)
    val statusMessageFactory1 = StatusMessageFactory(beaconChain1, forkIdHashProvider1)
    val initialExpectedBeaconBlockNumber = 1uL
    val p2PNetworkImpl1 =
      P2PNetworkImpl(
        privateKeyBytes = key1,
        p2pConfig =
          P2P(
            ipAddress = IPV4,
            port = PORT1,
            staticPeers = emptyList(),
          ),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory1,
        beaconChain = beaconChain1,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
        metricsSystem = TestMetrics.TestMetricsSystemAdapter,
        forkIdHashProvider = forkIdHashProvider1,
      )

    val beaconChain2 = InMemoryBeaconChain(genesisState)
    val forkIdHashProvider2 = createForkIdHashProvider(beaconChain1)
    val statusMessageFactory2 = StatusMessageFactory(beaconChain1, forkIdHashProvider1)
    val p2pNetworkImpl2 =
      P2PNetworkImpl(
        privateKeyBytes = key2,
        p2pConfig =
          P2P(
            ipAddress = IPV4,
            port = PORT2,
            staticPeers = emptyList(),
          ),
        chainId = chainId,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory2,
        beaconChain = beaconChain2,
        nextExpectedBeaconBlockNumber = initialExpectedBeaconBlockNumber,
        metricsSystem = TestMetrics.TestMetricsSystemAdapter,
        forkIdHashProvider = forkIdHashProvider2,
      )

    try {
      p2PNetworkImpl1.start()
      p2pNetworkImpl2.start()
      p2PNetworkImpl1.addStaticPeer(MultiaddrPeerAddress.fromAddress(PEER_ADDRESS_NODE_2))

      awaitUntilAsserted { assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      val clSyncPipelineImpl1 = CLSyncPipelineImpl(
        beaconChain = beaconChain1,
        validators = setOf(DataGenerators.randomValidator()),
        peerLookup = p2PNetworkImpl1.getPeerLookup(),
        besuMetrics = TestMetrics.TestMetricsSystemAdapter,
      )
      clSyncPipelineImpl1.start()

      val clSyncPipelineImpl2 = CLSyncPipelineImpl(
        beaconChain = beaconChain1,
        validators = setOf(DataGenerators.randomValidator()),
        peerLookup = p2PNetworkImpl1.getPeerLookup(),
        besuMetrics = TestMetrics.TestMetricsSystemAdapter,
      )
      clSyncPipelineImpl2.start()

      clSyncPipelineImpl1.setSyncTarget(2uL)
    } finally {
      p2PNetworkImpl1.stop()
      p2pNetworkImpl2.stop()
    }
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

  private fun assertNetworkHasPeers(
    network: P2PNetworkImpl,
    peers: Int,
  ) {
    assertThat(network.getPeers().count()).isEqualTo(peers)
  }

}
