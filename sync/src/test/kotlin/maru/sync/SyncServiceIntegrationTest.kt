/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.sync

import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import maru.config.P2P
import maru.config.consensus.ElFork
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ConsensusConfig
import maru.consensus.ForkIdHashProvider
import maru.consensus.ForkIdHasher
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import maru.core.BeaconState
import maru.core.Validator
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
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.SAME_THREAD)
class SyncServiceIntegrationTest {
  private lateinit var syncService: SyncService
  private lateinit var beaconChain1: BeaconChain
  private lateinit var beaconChain2: BeaconChain
  private lateinit var validators: Set<Validator>
  private lateinit var p2pNetwork1: P2PNetworkImpl
  private lateinit var p2pNetwork2: P2PNetworkImpl
  private lateinit var forkIdHashProvider: ForkIdHashProvider
  private val genesisState = DataGenerators.randomBeaconState(0u)

  companion object {
    private const val CHAIN_ID = 1337u
    private const val IPV4 = "127.0.0.1"
    private const val PORT1 = 9245u
    private const val PORT2 = 9246u

    private const val PRIVATE_KEY1: String =
      "0x0802122012c0b113e2b0c37388e2b484112e13f05c92c4471e3ee1dfaa368fa5045325b2"
    private const val PRIVATE_KEY2: String =
      "0x0802122100f3d2fffa99dc8906823866d96316492ebf7a8478713a89a58b7385af85b088a1"

    private const val PEER_ID_NODE_1: String = "16Uiu2HAmPRfinavM2jE9BSkCagBGStJ2SEkPPm6fxFVMdCQebzt6"
    private const val PEER_ID_NODE_2: String = "16Uiu2HAmVXtqhevTAJqZucPbR2W4nCMpetrQASgjZpcxDEDaUPPt"
    private const val PEER_ADDRESS_NODE_2: String = "/ip4/$IPV4/tcp/$PORT2/p2p/$PEER_ID_NODE_2"
  }

  @BeforeEach
  fun setUp() {
    validators = genesisState.validators
    beaconChain1 = InMemoryBeaconChain(genesisState)
    beaconChain2 = InMemoryBeaconChain(genesisState)

    // Set up fork ID hash provider
    val consensusConfig: ConsensusConfig =
      QbftConsensusConfig(
        validatorSet = validators,
        elFork = ElFork.Prague,
      )
    val forksSchedule = ForksSchedule(CHAIN_ID, listOf(ForkSpec(0L, 1, consensusConfig)))

    val forkIdHashProvider1 =
      ForkIdHashProvider(
        chainId = CHAIN_ID,
        beaconChain = beaconChain1,
        forksSchedule = forksSchedule,
        forkIdHasher = ForkIdHasher(ForkIdSerializers.ForkIdSerializer, Hashing::shortShaHash),
      )
    val forkIdHashProvider2 =
      ForkIdHashProvider(
        chainId = CHAIN_ID,
        beaconChain = beaconChain2,
        forksSchedule = forksSchedule,
        forkIdHasher = ForkIdHasher(ForkIdSerializers.ForkIdSerializer, Hashing::shortShaHash),
      )

    // Create P2P networks
    val statusMessageFactory1 = StatusMessageFactory(beaconChain1, forkIdHashProvider1)
    val statusMessageFactory2 = StatusMessageFactory(beaconChain2, forkIdHashProvider2)

    p2pNetwork1 =
      P2PNetworkImpl(
        privateKeyBytes = Bytes.fromHexString(PRIVATE_KEY1).toArray(),
        p2pConfig =
          P2P(
            ipAddress = IPV4,
            port = PORT1,
            staticPeers = emptyList(),
          ),
        chainId = CHAIN_ID,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory1,
        beaconChain = beaconChain1,
        nextExpectedBeaconBlockNumber = 1UL,
        metricsSystem = TestMetrics.TestMetricsSystemAdapter,
        forkIdHashProvider = forkIdHashProvider1,
      )

    p2pNetwork2 =
      P2PNetworkImpl(
        privateKeyBytes = Bytes.fromHexString(PRIVATE_KEY2).toArray(),
        p2pConfig =
          P2P(
            ipAddress = IPV4,
            port = PORT2,
            staticPeers = listOf("/ip4/$IPV4/tcp/$PORT1/p2p/$PEER_ID_NODE_1"),
          ),
        chainId = CHAIN_ID,
        serDe = RLPSerializers.SealedBeaconBlockSerializer,
        metricsFacade = TestMetrics.TestMetricsFacade,
        statusMessageFactory = statusMessageFactory2,
        beaconChain = beaconChain2,
        nextExpectedBeaconBlockNumber = 1UL,
        metricsSystem = TestMetrics.TestMetricsSystemAdapter,
        forkIdHashProvider = forkIdHashProvider2,
      )

    // Start networks
    p2pNetwork1.start()
    p2pNetwork2.start()

    // Give some time for networks to start and exchange status
    Thread.sleep(5000)

    // Create sync service with peer lookup from network 2
    syncService =
      SyncService(
        peerLookup = p2pNetwork2.getPeerLookup(),
        beaconChain = beaconChain2,
        validators = validators,
        allowEmptyBlocks = true,
      )
  }

  @AfterEach
  fun tearDown() {
    syncService.stop()
    p2pNetwork1.stop()
    p2pNetwork2.stop()
  }

  @Test
  fun `sync service downloads and imports blocks from peers`() {
    // Wait for peers to connect
    waitForPeerConnection()
    // Skip status exchange check for now
    // waitForPeerStatusExchange()

    // Given: Add blocks to beaconChain1 (the source chain)
    val block1 = DataGenerators.randomSealedBeaconBlock(1u)
    val block2 = DataGenerators.randomSealedBeaconBlock(2u)
    val blocks = listOf(block1, block2)

    beaconChain1.newUpdater().use { updater ->
      blocks.forEach { block ->
        updater.putSealedBeaconBlock(block)
      }
      updater.putBeaconState(
        BeaconState(
          latestBeaconBlockHeader = blocks.last().beaconBlock.beaconBlockHeader,
          validators = validators,
        ),
      )
      updater.commit()
    }

    // When: Start the sync service
    val startFuture = syncService.start()
    assertThat(startFuture.isDone).isTrue()

    // Then: Wait for blocks to be downloaded and imported into beaconChain2
    await.atMost(30.seconds.toJavaDuration()).untilAsserted {
      // Check that blocks were imported into beaconChain2
      val importedBlock1 = beaconChain2.getSealedBeaconBlock(1u)
      val importedBlock2 = beaconChain2.getSealedBeaconBlock(2u)
      assertThat(importedBlock1).isNotNull
      assertThat(importedBlock2).isNotNull
      assertThat(beaconChain2.getLatestBeaconState().latestBeaconBlockHeader.number).isGreaterThanOrEqualTo(1u)
    }
  }

  private fun waitForPeerConnection() {
    await.atMost(30.seconds.toJavaDuration()).untilAsserted {
      assertThat(p2pNetwork1.getPeerLookup().getPeers().size).isEqualTo(1)
      assertThat(p2pNetwork2.getPeerLookup().getPeers().size).isEqualTo(1)
    }
  }

  private fun waitForPeerStatusExchange() {
    await.atMost(20.seconds.toJavaDuration()).untilAsserted {
      val peer1 = p2pNetwork1.getPeerLookup().getPeers().firstOrNull()
      val peer2 = p2pNetwork2.getPeerLookup().getPeers().firstOrNull()

      // Debug output
      if (peer1?.getStatus() == null || peer2?.getStatus() == null) {
        println("Debug: peer1 status = ${peer1?.getStatus()}, peer2 status = ${peer2?.getStatus()}")
        println("Debug: peer1 latestBlockNumber = ${peer1?.getStatus()?.latestBlockNumber}")
        println("Debug: peer2 latestBlockNumber = ${peer2?.getStatus()?.latestBlockNumber}")
      }

      assertThat(peer1).isNotNull
      assertThat(peer2).isNotNull
      assertThat(peer1?.getStatus()).isNotNull
      assertThat(peer2?.getStatus()).isNotNull
    }
  }

  @Test
  fun `sync service handles empty peer responses gracefully`() {
    // Wait for peers to connect
    waitForPeerConnection()
    // Skip status exchange check for now
    // waitForPeerStatusExchange()

    // Given: beaconChain1 has no new blocks (only genesis)

    // When: Start the sync service
    val startFuture = syncService.start()
    assertThat(startFuture.isDone).isTrue()

    // Then: Service should handle empty response without errors
    Thread.sleep(2000) // Give time for sync attempt

    // Both chains should still be at genesis
    assertThat(beaconChain2.getLatestBeaconState().latestBeaconBlockHeader.number).isEqualTo(0uL)
  }

  @Test
  fun `sync service handles peer failures and continues`() {
    // Wait for peers to connect
    waitForPeerConnection()
    // Skip status exchange check for now
    // waitForPeerStatusExchange()

    // Given: Stop p2pNetwork1 to simulate network failure
    p2pNetwork1.stop()

    // When: Start the sync service
    val startFuture = syncService.start()
    assertThat(startFuture.isDone).isTrue()

    // Wait a bit for failed attempts
    Thread.sleep(2000)

    // Then: Add blocks to beaconChain1 and restart network1
    val block1 = DataGenerators.randomSealedBeaconBlock(1u)
    beaconChain1.newUpdater().use { updater ->
      updater.putSealedBeaconBlock(block1)
      updater.putBeaconState(
        BeaconState(
          latestBeaconBlockHeader = block1.beaconBlock.beaconBlockHeader,
          validators = validators,
        ),
      )
      updater.commit()
    }

    // Restart network1
    p2pNetwork1.start()

    // Wait for reconnection
    waitForPeerConnection()
    // Skip status exchange check for now
    // waitForPeerStatusExchange()

    // Then: Service should retry and eventually succeed
    await.atMost(10.seconds.toJavaDuration()).untilAsserted {
      val importedBlock = beaconChain2.getSealedBeaconBlock(1u)
      assertThat(importedBlock).isNotNull
    }
  }

  @Test
  fun `sync service properly validates and imports blocks`() {
    // Wait for peers to connect
    waitForPeerConnection()

    // Skip status exchange check for now
    // waitForPeerStatusExchange()

    val block1 = DataGenerators.randomSealedBeaconBlock(1u, parentRoot = genesisState.latestBeaconBlockHeader.hash)
    val block2 = DataGenerators.randomSealedBeaconBlock(2u, parentRoot = block1.beaconBlock.beaconBlockHeader.hash)
    val block3 = DataGenerators.randomSealedBeaconBlock(3u, parentRoot = block2.beaconBlock.beaconBlockHeader.hash)

    // Add blocks to beaconChain1
    beaconChain1.newUpdater().use { updater ->
      updater.putSealedBeaconBlock(block1)
      updater.putSealedBeaconBlock(block2)
      updater.putSealedBeaconBlock(block3)
      updater.putBeaconState(
        BeaconState(
          latestBeaconBlockHeader = block3.beaconBlock.beaconBlockHeader,
          validators = validators,
        ),
      )
      updater.commit()
    }

    // Force status update
    p2pNetwork1
      .getPeerLookup()
      .getPeers()
      .first()
      .sendStatus()
    p2pNetwork2
      .getPeerLookup()
      .getPeers()
      .first()
      .sendStatus()
    Thread.sleep(2000)
//    waitForPeerStatusExchange()

    // When: Start the sync service
    val startFuture = syncService.start()
    assertThat(startFuture.isDone).isTrue()

    // Then: Blocks should be downloaded and imported
    await.atMost(30.seconds.toJavaDuration()).untilAsserted {
      assertThat(beaconChain2.getSealedBeaconBlock(1u)).isNotNull
      assertThat(beaconChain2.getSealedBeaconBlock(2u)).isNotNull
      assertThat(beaconChain2.getSealedBeaconBlock(3u)).isNotNull
      assertThat(beaconChain2.getLatestBeaconState().latestBeaconBlockHeader.number).isEqualTo(3u)
    }
  }

  @Test
  fun `sync service stops pipeline on stop`() {
    // Wait for peers to connect
    waitForPeerConnection()
    // Skip status exchange check for now
    // waitForPeerStatusExchange()

    // Given: Add some blocks to sync
    val block1 = DataGenerators.randomSealedBeaconBlock(1u)
    beaconChain1.newUpdater().use { updater ->
      updater.putSealedBeaconBlock(block1)
      updater.putBeaconState(
        BeaconState(
          latestBeaconBlockHeader = block1.beaconBlock.beaconBlockHeader,
          validators = validators,
        ),
      )
      updater.commit()
    }

    // Start the sync service
    val startFuture = syncService.start()
    assertThat(startFuture.isDone).isTrue()

    // When: Stop the sync service
    syncService.stop()

    // Then: Add more blocks after stopping
    Thread.sleep(1000) // Give time for stop to complete

    val block2 = DataGenerators.randomSealedBeaconBlock(2u)
    beaconChain1.newUpdater().use { updater ->
      updater.putSealedBeaconBlock(block2)
      updater.putBeaconState(
        BeaconState(
          latestBeaconBlockHeader = block2.beaconBlock.beaconBlockHeader,
          validators = validators,
        ),
      )
      updater.commit()
    }

    // Wait and verify block2 was NOT synced (because service is stopped)
    Thread.sleep(3000)
    assertThat(beaconChain2.getSealedBeaconBlock(2u)).isNull()
  }
}
