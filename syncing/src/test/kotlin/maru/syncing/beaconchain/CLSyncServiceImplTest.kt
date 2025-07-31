/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing.beaconchain

import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import maru.config.P2P
import maru.config.consensus.ElFork
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ConsensusConfig
import maru.consensus.ForkIdHashProvider
import maru.consensus.ForkIdHasher
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import maru.consensus.StaticValidatorProvider
import maru.consensus.qbft.DelayedQbftBlockCreator
import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.Seal
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.core.ext.metrics.TestMetrics.TestMetricsFacade
import maru.core.ext.metrics.TestMetrics.TestMetricsSystemAdapter
import maru.crypto.Hashing
import maru.database.BeaconChain
import maru.database.InMemoryBeaconChain
import maru.extensions.fromHexToByteArray
import maru.p2p.P2PNetworkImpl
import maru.p2p.messages.StatusMessageFactory
import maru.serialization.ForkIdSerializers
import maru.serialization.rlp.RLPSerializers
import maru.syncing.beaconchain.pipeline.BeaconChainDownloadPipelineFactory.Config
import net.consensys.linea.metrics.Counter
import net.consensys.linea.metrics.MetricsFacade
import org.apache.tuweni.bytes.Bytes32
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.hyperledger.besu.crypto.KeyPair
import org.hyperledger.besu.crypto.SignatureAlgorithm
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory
import org.hyperledger.besu.ethereum.core.Util
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tech.pegasys.teku.networking.p2p.libp2p.MultiaddrPeerAddress

class CLSyncServiceImplTest {
  companion object {
    private const val CHAIN_ID = 1337u
    private const val IPV4 = "127.0.0.1"
    private const val PEER_ID_NODE_2: String = "16Uiu2HAmVXtqhevTAJqZucPbR2W4nCMpetrQASgjZpcxDEDaUPPt"

    private val key1 = "0x0802122012c0b113e2b0c37388e2b484112e13f05c92c4471e3ee1dfaa368fa5045325b2".fromHexToByteArray()
    private val key2 =
      "0x0802122100f3d2fffa99dc8906823866d96316492ebf7a8478713a89a58b7385af85b088a1"
        .fromHexToByteArray()
  }

  @Test
  fun `sync service downloads and imports blocks from another node`() {
    val signatureAlgorithm = SignatureAlgorithmFactory.getInstance()
    val keypair = signatureAlgorithm.generateKeyPair()
    val validator = Validator(Util.publicKeyToAddress(keypair.publicKey).toArray())
    val validators = setOf(validator)
    val genesisTimestamp = DataGenerators.randomTimestamp()

    val (genesisBeaconState, genesisBeaconBlock) = genesisState(genesisTimestamp, validators)
    val beaconChain1 = InMemoryBeaconChain(genesisBeaconState, genesisBeaconBlock)
    val beaconChain2 = InMemoryBeaconChain(genesisBeaconState, genesisBeaconBlock)
    createBlocks(
      beaconChain = beaconChain2,
      genesisBeaconBlock = genesisBeaconBlock,
      genesisTimestamp = genesisTimestamp,
      validators = validators,
      signatureAlgorithm = signatureAlgorithm,
      keypair = keypair,
    )

    val port1 = findFreePort()
    val port2 = findFreePort()
    val p2PNetworkImpl1 = createNetwork(beaconChain1, key1, port1)
    val p2pNetworkImpl2 = createNetwork(beaconChain2, key2, port2)

    try {
      p2PNetworkImpl1.start()
      p2pNetworkImpl2.start()
      p2PNetworkImpl1.addStaticPeer(createPeerAddress(port2))

      awaitUntilAsserted { assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      val clSyncServiceImpl1 =
        CLSyncServiceImpl(
          beaconChain = beaconChain1,
          validatorProvider = StaticValidatorProvider(validators),
          allowEmptyBlocks = true,
          peerLookup = p2PNetworkImpl1.getPeerLookup(),
          besuMetrics = TestMetricsSystemAdapter,
          metricsFacade = TestMetricsFacade,
          pipelineConfig = Config(blocksBatchSize = 10u, blocksParallelism = 1u),
        )
      clSyncServiceImpl1.start()

      val synced = AtomicBoolean(false)
      clSyncServiceImpl1.setSyncTarget(100uL)
      clSyncServiceImpl1.onSyncComplete { synced.set(true) }
      awaitUntilAsserted { assertThat(synced).isTrue() }
      assertThat(beaconChain1.getLatestBeaconState().latestBeaconBlockHeader.number).isEqualTo(100uL)
      assertThat(beaconChain1.getLatestBeaconState()).isEqualTo(beaconChain2.getLatestBeaconState())
      for (i in 1uL..100uL) {
        assertThat(beaconChain1.getSealedBeaconBlock(i)).isEqualTo(beaconChain2.getSealedBeaconBlock(i))
        assertThat(beaconChain1.getBeaconState(i)).isEqualTo(beaconChain2.getBeaconState(i))
      }
    } finally {
      p2PNetworkImpl1.stop()
      p2pNetworkImpl2.stop()
    }
  }

  @Test
  fun `sync target can be updated ahead of current target and continue downloading`() {
    val signatureAlgorithm = SignatureAlgorithmFactory.getInstance()
    val keypair = signatureAlgorithm.generateKeyPair()
    val validator = Validator(Util.publicKeyToAddress(keypair.publicKey).toArray())
    val validators = setOf(validator)
    val genesisTimestamp = DataGenerators.randomTimestamp()

    val (genesisBeaconState, genesisBeaconBlock) = genesisState(genesisTimestamp, validators)
    val beaconChain1 = InMemoryBeaconChain(genesisBeaconState, genesisBeaconBlock)
    val beaconChain2 = InMemoryBeaconChain(genesisBeaconState, genesisBeaconBlock)
    createBlocks(
      beaconChain = beaconChain2,
      genesisBeaconBlock = genesisBeaconBlock,
      genesisTimestamp = genesisTimestamp,
      validators = validators,
      signatureAlgorithm = signatureAlgorithm,
      keypair = keypair,
    )

    val port1 = findFreePort()
    val port2 = findFreePort()
    val p2PNetworkImpl1 = createNetwork(beaconChain1, key1, port1)
    val p2pNetworkImpl2 = createNetwork(beaconChain2, key2, port2)

    try {
      p2PNetworkImpl1.start()
      p2pNetworkImpl2.start()
      p2PNetworkImpl1.addStaticPeer(createPeerAddress(port2))

      awaitUntilAsserted { assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      val clSyncServiceImpl1 =
        CLSyncServiceImpl(
          beaconChain = beaconChain1,
          validatorProvider = StaticValidatorProvider(validators),
          allowEmptyBlocks = true,
          peerLookup = p2PNetworkImpl1.getPeerLookup(),
          besuMetrics = TestMetricsSystemAdapter,
          metricsFacade = TestMetricsFacade,
          pipelineConfig = Config(blocksBatchSize = 10u, blocksParallelism = 1u),
        )
      clSyncServiceImpl1.start()

      // sync to block 50
      val synced = AtomicBoolean(false)
      clSyncServiceImpl1.setSyncTarget(50uL)
      clSyncServiceImpl1.onSyncComplete { synced.set(true) }
      awaitUntilAsserted { assertThat(synced).isTrue() }

      assertThat(beaconChain1.getLatestBeaconState().latestBeaconBlockHeader.number).isEqualTo(50uL)
      assertThat(beaconChain1.getLatestBeaconState()).isEqualTo(beaconChain2.getBeaconState(50uL))
      for (i in 1uL..50uL) {
        assertThat(beaconChain1.getSealedBeaconBlock(i)).isEqualTo(beaconChain2.getSealedBeaconBlock(i))
        assertThat(beaconChain1.getBeaconState(i)).isEqualTo(beaconChain2.getBeaconState(i))
      }

      // update sync target to 100
      synced.set(false)
      clSyncServiceImpl1.setSyncTarget(100uL)
      awaitUntilAsserted { assertThat(synced).isTrue() }

      assertThat(beaconChain1.getLatestBeaconState().latestBeaconBlockHeader.number).isEqualTo(100uL)
      assertThat(beaconChain1.getLatestBeaconState()).isEqualTo(beaconChain2.getLatestBeaconState())
      for (i in 1uL..100uL) {
        assertThat(beaconChain1.getSealedBeaconBlock(i)).isEqualTo(beaconChain2.getSealedBeaconBlock(i))
        assertThat(beaconChain1.getBeaconState(i)).isEqualTo(beaconChain2.getBeaconState(i))
      }
    } finally {
      p2PNetworkImpl1.stop()
      p2pNetworkImpl2.stop()
    }
  }

  @Test
  fun `sync service download restarts on errors`() {
    val signatureAlgorithm = SignatureAlgorithmFactory.getInstance()
    val keypair = signatureAlgorithm.generateKeyPair()
    val validator = Validator(Util.publicKeyToAddress(keypair.publicKey).toArray())
    val validators = setOf(validator)
    val genesisTimestamp = DataGenerators.randomTimestamp()

    val (genesisBeaconState, genesisBeaconBlock) = genesisState(genesisTimestamp, validators)
    val beaconChain1 = InMemoryBeaconChain(genesisBeaconState, genesisBeaconBlock)
    val beaconChain2 = spy(InMemoryBeaconChain(genesisBeaconState, genesisBeaconBlock))

    createBlocks(
      beaconChain = beaconChain2,
      genesisBeaconBlock = genesisBeaconBlock,
      genesisTimestamp = genesisTimestamp,
      validators = validators,
      signatureAlgorithm = signatureAlgorithm,
      keypair = keypair,
    )

    val port1 = findFreePort()
    val port2 = findFreePort()
    val p2PNetworkImpl1 = createNetwork(beaconChain1, key1, port1)
    val p2pNetworkImpl2 = createNetwork(beaconChain2, key2, port2)
    val peerLookup = spy(p2PNetworkImpl1.getPeerLookup())

    // Fail the first two calls to getPeers() to simulate failure getting peers and not downloading blocks
    var retries = 0
    doAnswer {
      if (retries < 2) {
        retries++
        throw IllegalStateException("Simulated failure for testing")
      } else {
        it.callRealMethod()
      }
    }.whenever(peerLookup).getPeers()

    try {
      p2PNetworkImpl1.start()
      p2pNetworkImpl2.start()
      p2PNetworkImpl1.addStaticPeer(createPeerAddress(port2))

      awaitUntilAsserted { assertNetworkHasPeers(network = p2PNetworkImpl1, peers = 1) }
      awaitUntilAsserted { assertNetworkHasPeers(network = p2pNetworkImpl2, peers = 1) }

      val metricsFacade = mock(MetricsFacade::class.java)
      val retriesCounter = mock(Counter::class.java)
      whenever(metricsFacade.createCounter(any(), any(), any(), any())).thenReturn(retriesCounter)

      val clSyncServiceImpl =
        CLSyncServiceImpl(
          beaconChain = beaconChain1,
          validatorProvider = StaticValidatorProvider(validators),
          allowEmptyBlocks = true,
          peerLookup = peerLookup,
          besuMetrics = TestMetricsSystemAdapter,
          metricsFacade = metricsFacade,
          pipelineConfig = Config(blocksBatchSize = 10u, blocksParallelism = 1u),
        )
      clSyncServiceImpl.start()

      val synced = AtomicBoolean(false)
      clSyncServiceImpl.setSyncTarget(100uL)
      clSyncServiceImpl.onSyncComplete { synced.set(true) }
      awaitUntilAsserted { assertThat(synced).isTrue() }

      assertThat(beaconChain1.getLatestBeaconState().latestBeaconBlockHeader.number).isEqualTo(100uL)
      assertThat(beaconChain1.getLatestBeaconState()).isEqualTo(beaconChain2.getLatestBeaconState())
      for (i in 0uL..2uL) {
        assertThat(beaconChain1.getSealedBeaconBlock(i)).isEqualTo(beaconChain2.getSealedBeaconBlock(i))
        assertThat(beaconChain1.getBeaconState(i)).isEqualTo(beaconChain2.getBeaconState(i))
      }

      verify(retriesCounter, times(retries)).increment()
    } finally {
      p2PNetworkImpl1.stop()
      p2pNetworkImpl2.stop()
    }
  }

  private fun createPeerAddress(port: UInt): MultiaddrPeerAddress =
    MultiaddrPeerAddress.fromAddress("/ip4/$IPV4/tcp/$port/p2p/$PEER_ID_NODE_2")

  private fun genesisState(
    genesisTimestamp: ULong,
    validators: Set<Validator>,
  ): Pair<BeaconState, SealedBeaconBlock> {
    val genesisBeaconBlockHeader =
      BeaconBlockHeader(
        number = 0uL,
        round = 0u,
        timestamp = genesisTimestamp,
        proposer = validators.first(),
        parentRoot = Random.nextBytes(32),
        stateRoot = Random.nextBytes(32),
        bodyRoot = Random.nextBytes(32),
        headerHashFunction = RLPSerializers.DefaultHeaderHashFunction,
      )
    val genesisBeaconState =
      BeaconState(
        latestBeaconBlockHeader = genesisBeaconBlockHeader,
        validators = validators,
      )

    val genesisBeaconBlock =
      SealedBeaconBlock(
        beaconBlock =
          BeaconBlock(
            beaconBlockHeader = genesisBeaconBlockHeader,
            beaconBlockBody = DataGenerators.randomBeaconBlockBody(),
          ),
        commitSeals = setOf(Seal(Random.nextBytes(96))),
      )
    return Pair(genesisBeaconState, genesisBeaconBlock)
  }

  private fun createNetwork(
    beaconChain: BeaconChain,
    key: ByteArray,
    port: UInt,
  ): P2PNetworkImpl {
    val forkIdHashProvider = createForkIdHashProvider(beaconChain)
    val statusMessageFactory = StatusMessageFactory(beaconChain, forkIdHashProvider)
    val p2pNetworkImpl =
      P2PNetworkImpl(
        privateKeyBytes = key,
        p2pConfig =
          P2P(
            ipAddress = IPV4,
            port = port,
            staticPeers = emptyList(),
          ),
        chainId = CHAIN_ID,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetricsFacade,
        statusMessageFactory = statusMessageFactory,
        beaconChain = beaconChain,
        metricsSystem = TestMetricsSystemAdapter,
        forkIdHashProvider = forkIdHashProvider,
        isBlockImportEnabledProvider = { true },
      )
    return p2pNetworkImpl
  }

  private fun createBlocks(
    beaconChain: BeaconChain,
    genesisBeaconBlock: SealedBeaconBlock,
    genesisTimestamp: ULong,
    validators: Set<Validator>,
    signatureAlgorithm: SignatureAlgorithm,
    keypair: KeyPair,
  ) {
    val updater = beaconChain.newUpdater()
    var parentSealedBeaconBlock = genesisBeaconBlock
    for (i in 1uL..100uL) {
      val beaconBlock =
        DelayedQbftBlockCreator.createBeaconBlock(
          parentSealedBeaconBlock = parentSealedBeaconBlock,
          executionPayload = DataGenerators.randomExecutionPayload(),
          round = 0,
          timestamp = genesisTimestamp + i,
          proposer = validators.first().address,
          validators = validators,
        )
      val seal = signatureAlgorithm.sign(Bytes32.wrap(beaconBlock.beaconBlockHeader.hash), keypair)
      val sealedBlock =
        SealedBeaconBlock(
          beaconBlock = beaconBlock,
          setOf(Seal(seal.encodedBytes().toArray())),
        )
      val beaconState =
        BeaconState(
          latestBeaconBlockHeader = beaconBlock.beaconBlockHeader,
          validators = validators,
        )
      updater.putSealedBeaconBlock(sealedBlock)
      updater.putBeaconState(beaconState)
      parentSealedBeaconBlock = sealedBlock
    }
    updater.commit()
  }

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
    val forksSchedule = ForksSchedule(CHAIN_ID, listOf(ForkSpec(0L, 1, consensusConfig)))

    return ForkIdHashProvider(
      chainId = CHAIN_ID,
      beaconChain = beaconChain,
      forksSchedule = forksSchedule,
      forkIdHasher = ForkIdHasher(ForkIdSerializers.ForkIdSerializer, Hashing::shortShaHash),
    )
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

  private fun findFreePort(): UInt =
    runCatching {
      ServerSocket(0).use { socket ->
        socket.reuseAddress = true
        socket.localPort.toUInt()
      }
    }.getOrElse {
      throw IllegalStateException("Could not find a free port", it)
    }
}
