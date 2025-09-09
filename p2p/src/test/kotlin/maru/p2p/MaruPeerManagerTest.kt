/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import kotlin.time.Duration.Companion.seconds
import maru.config.P2PConfig
import maru.config.SyncingConfig
import maru.config.consensus.ElFork
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ConsensusConfig
import maru.consensus.ForkIdHashProvider
import maru.consensus.ForkIdHashProviderImpl
import maru.consensus.ForkIdHasher
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import maru.core.ext.DataGenerators
import maru.crypto.Hashing
import maru.database.InMemoryBeaconChain
import maru.p2p.messages.Status
import maru.serialization.ForkIdSerializer
import maru.syncing.CLSyncStatus
import maru.syncing.ELSyncStatus
import maru.syncing.SyncStatusProvider
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.time.SystemTimeProvider
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.network.PeerAddress
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer
import tech.pegasys.teku.networking.p2p.reputation.ReputationAdjustment
import tech.pegasys.teku.networking.p2p.network.P2PNetwork as TekuP2PNetwork

class MaruPeerManagerTest {
  companion object {
    private val chainId = 1337u
    private val beaconChain = InMemoryBeaconChain(DataGenerators.randomBeaconState(number = 0u, timestamp = 0u))
    val reputationManager =
      MaruReputationManager(NoOpMetricsSystem(), SystemTimeProvider(), { _: NodeId -> false }, P2PConfig.Reputation())

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
      val forksSchedule = ForksSchedule(chainId, listOf(ForkSpec(0UL, 1U, consensusConfig)))

      return ForkIdHashProviderImpl(
        chainId = chainId,
        beaconChain = beaconChain,
        forksSchedule = forksSchedule,
        forkIdHasher = ForkIdHasher(ForkIdSerializer, Hashing::shortShaHash),
      )
    }

    val forkIdHashProvider: ForkIdHashProvider = createForkIdHashProvider()

    private fun createSyncStatusProvider(): SyncStatusProvider =
      object : SyncStatusProvider {
        override fun getCLSyncStatus(): CLSyncStatus = CLSyncStatus.SYNCED

        override fun getElSyncStatus(): ELSyncStatus = ELSyncStatus.SYNCED

        override fun onClSyncStatusUpdate(handler: (newStatus: CLSyncStatus) -> Unit) {}

        override fun onElSyncStatusUpdate(handler: (newStatus: ELSyncStatus) -> Unit) {}

        override fun isBeaconChainSynced(): Boolean = true

        override fun isELSynced(): Boolean = true

        override fun onBeaconSyncComplete(handler: () -> Unit) {}

        override fun onFullSyncComplete(handler: () -> Unit) {}

        override fun getBeaconSyncDistance(): ULong = 0UL

        override fun getCLSyncTarget(): ULong = 0UL
      }

    val syncStatusProvider: SyncStatusProvider = createSyncStatusProvider()

    val statusFuture =
      SafeFuture<Status>.completedFuture(
        Status(
          forkIdHash = forkIdHashProvider.currentForkIdHash(),
          latestBlockNumber = 0u,
          latestStateRoot = ByteArray(32) { 0x00 },
        ),
      )
  }

  @Test
  fun `creates maru peer through factory when peer connects`() {
    val mockScheduler = mock<ScheduledExecutorService>()
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2PConfig>()
    val syncConfig = mock<SyncingConfig>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(peer.address).thenReturn(mock())
    whenever(peer.connectionInitiatedLocally()).thenReturn(true)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.connectionInitiatedLocally()).thenReturn(true)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusFuture)
    whenever(maruPeer.address).thenReturn(mock())
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkIdHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig = syncConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
      )
    manager.scheduler = mockScheduler
    manager.start(discoveryService = null, p2pNetwork = mock())
    manager.onConnect(peer)

    verify(maruPeerFactory).createMaruPeer(peer)
  }

  @Test
  fun `onConnect successfully connects peer when all conditions are met`() {
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2PConfig>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()
    val syncConfig = mock<SyncingConfig>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(peer.address).thenReturn(mock())
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.connectionInitiatedLocally()).thenReturn(true)
    whenever(maruPeer.sendStatus()).thenReturn(SafeFuture.completedFuture(Unit))
    whenever(maruPeer.address).thenReturn(mock())
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusFuture) // Uses correct fork ID hash
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkIdHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig = syncConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
      )
    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    manager.onConnect(peer)

    // Verify peer was not disconnected
    verify(maruPeer, never()).disconnectCleanly(any())
    verify(peer, never()).disconnectCleanly(any())

    // Verify peer is stored and retrievable
    assertThat(manager.getPeer(nodeId)).isEqualTo(maruPeer)
  }

  @Test
  fun `onConnect disconnects peer when max peers limit exceeded`() {
    val maruPeerFactory = mock<MaruPeerFactory>()
    val p2pConfig = mock<P2PConfig>()
    val syncConfig = mock<SyncingConfig>()

    whenever(p2pConfig.maxPeers).thenReturn(5)
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkIdHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig = syncConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
      )
    val mockScheduler = mock<ScheduledExecutorService>()
    manager.scheduler = mockScheduler

    val p2pNetwork = mock<P2PNetwork<Peer>>()
    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    // Add 5 connected peers to equal the maxPeers limit of 5
    addConnectedPeers(5, manager)

    val peer = mock<Peer>()
    whenever(peer.id).thenReturn(mock())

    manager.onConnect(peer)

    verify(peer).disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
  }

  @Test
  fun `onConnect disconnects peer when fork ID hash mismatch`() {
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2PConfig>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()
    val syncConfig = mock<SyncingConfig>()

    // Create status with different fork ID hash
    val differentForkIdHash = ByteArray(32) { 0xFF.toByte() }
    val statusWithDifferentForkId =
      SafeFuture.completedFuture(
        Status(
          forkIdHash = differentForkIdHash,
          latestBlockNumber = 0u,
          latestStateRoot = ByteArray(32) { 0x00 },
        ),
      )

    whenever(peer.id).thenReturn(nodeId)
    whenever(peer.address).thenReturn(mock())
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(p2pConfig.statusUpdate).thenReturn(P2PConfig.StatusUpdate())
    whenever(p2pNetwork.peerCount).thenReturn(5)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusWithDifferentForkId)
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkIdHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig = syncConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
      )
    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    manager.onConnect(peer)

    verify(maruPeer).disconnectCleanly(DisconnectReason.IRRELEVANT_NETWORK)
    assertThat(manager.getPeer(nodeId)).isNull()
  }

  @Test
  fun `onConnect disconnects peer when too far behind during syncing`() {
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2PConfig>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()
    val syncConfig = mock<SyncingConfig>()

    // Create sync status provider that returns SYNCING status
    val syncingStatusProvider =
      object : SyncStatusProvider {
        override fun getCLSyncStatus(): CLSyncStatus = CLSyncStatus.SYNCING

        override fun getElSyncStatus(): ELSyncStatus = ELSyncStatus.SYNCED

        override fun onClSyncStatusUpdate(handler: (newStatus: CLSyncStatus) -> Unit) {}

        override fun onElSyncStatusUpdate(handler: (newStatus: ELSyncStatus) -> Unit) {}

        override fun isBeaconChainSynced(): Boolean = false

        override fun isELSynced(): Boolean = true

        override fun onBeaconSyncComplete(handler: () -> Unit) {}

        override fun onFullSyncComplete(handler: () -> Unit) {}

        override fun getBeaconSyncDistance(): ULong = 100u

        override fun getCLSyncTarget(): ULong = 1000u
      }

    // Create status with peer far behind (target - peer > blocksBehindThreshold)
    val statusFarBehind =
      SafeFuture.completedFuture(
        Status(
          forkIdHash = forkIdHashProvider.currentForkIdHash(),
          latestBlockNumber = 900u, // 1000 (sync target) - 900 = 100 > 32 (blocksBehindThreshold)
          latestStateRoot = ByteArray(32) { 0x00 },
        ),
      )

    whenever(peer.id).thenReturn(nodeId)
    whenever(peer.address).thenReturn(mock())
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(p2pNetwork.peerCount).thenReturn(5)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusFarBehind)
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkIdHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncingStatusProvider },
        syncConfig = syncConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
      )
    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    manager.onConnect(peer)

    verify(maruPeer).disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
    assertThat(manager.getPeer(nodeId)).isNull()
  }

  @Test
  fun `onConnect disconnects peer when behind and too many peers behind while synced`() {
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2PConfig>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()
    val syncConfig = mock<SyncingConfig>()

    // Create status with peer behind current head
    val currentHead = 100uL
    val statusBehind =
      SafeFuture.completedFuture(
        Status(
          forkIdHash = forkIdHashProvider.currentForkIdHash(),
          latestBlockNumber = 50u, // Behind current head
          latestStateRoot = ByteArray(32) { 0x00 },
        ),
      )

    // Mock beacon chain to return current head
    val mockBeaconState = DataGenerators.randomBeaconState(number = currentHead, timestamp = 0uL)
    val mockBeaconChain = mock<maru.database.BeaconChain>()
    whenever(mockBeaconChain.getLatestBeaconState()).thenReturn(mockBeaconState)

    whenever(peer.id).thenReturn(nodeId)
    whenever(peer.address).thenReturn(mock())
    whenever(p2pConfig.maxPeers).thenReturn(10) // maxPeers / 10 = 1, so 1 peer behind is too many
    whenever(p2pNetwork.peerCount).thenReturn(5)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusBehind)
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkIdHashProvider = forkIdHashProvider,
        beaconChain = mockBeaconChain,
        syncStatusProviderProvider = { syncStatusProvider }, // SYNCED status
        syncConfig = syncConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
      )

    // Add one peer that's already behind to trigger "too many peers behind" condition
    val existingPeer = mock<MaruPeer>()
    val existingPeerStatus = mock<Status>()
    val existingPeerNodeId = mock<NodeId>()

    whenever(existingPeerStatus.latestBlockNumber).thenReturn(40u) // Behind threshold
    whenever(existingPeer.getStatus()).thenReturn(existingPeerStatus)
    whenever(existingPeer.id).thenReturn(existingPeerNodeId)

    addConnectedPeer(manager, existingPeer)

    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    manager.onConnect(peer)

    verify(maruPeer).disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
    assertThat(manager.getPeer(nodeId)).isNull()
  }

  @Test
  fun `onConnect handles peer during status exchange phase`() {
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2PConfig>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()
    val syncConfig = mock<SyncingConfig>()

    // Create a future that hasn't completed yet to simulate status exchange in progress
    val pendingStatusFuture = SafeFuture<Status>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(peer.address).thenReturn(mock())
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(p2pNetwork.peerCount).thenReturn(5)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(pendingStatusFuture)
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkIdHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig = syncConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
      )
    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    manager.onConnect(peer)

    // Verify peer is in status exchanging phase
    assertThat(manager.getPeer(nodeId)).isEqualTo(maruPeer)

    // Complete the status exchange successfully
    pendingStatusFuture.complete(
      Status(
        forkIdHash = forkIdHashProvider.currentForkIdHash(),
        latestBlockNumber = 0u,
        latestStateRoot = ByteArray(32) { 0x00 },
      ),
    )

    // Verify peer is still available after successful status exchange
    assertThat(manager.getPeer(nodeId)).isEqualTo(maruPeer)
  }

  @Test
  fun `does not connect or add peer if reputation manager disallows connection`() {
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2PConfig>()
    val p2pNetwork = mock<TekuP2PNetwork<Peer>>()
    val address = mock<PeerAddress>()

    val reputationManager =
      MaruReputationManager(
        NoOpMetricsSystem(),
        SystemTimeProvider(),
        { _: NodeId -> false }, // always banned
        P2PConfig.Reputation(),
      )

    whenever(peer.id).thenReturn(nodeId)
    whenever(peer.address).thenReturn(address)
    whenever(address.id).thenReturn(nodeId)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(p2pNetwork.peerCount).thenReturn(0)

    reputationManager.adjustReputation(peer.address, ReputationAdjustment.LARGE_PENALTY)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkIdHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig =
          SyncingConfig(
            syncTargetSelection = SyncingConfig.SyncTargetSelection.Highest,
            peerChainHeightPollingInterval = 1.seconds,
            elSyncStatusRefreshInterval = 1.seconds,
          ),
        reputationManager = reputationManager,
        isStaticPeer = { false },
      )
    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    manager.onConnect(peer)

    verify(maruPeerFactory, never()).createMaruPeer(peer)
    assertThat(manager.getPeer(nodeId)).isNull()
  }

  @Test
  fun `connects and adds peer if reputation manager allows connection`() {
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2PConfig>()
    val reputationManager = mock<MaruReputationManager>()
    val p2pNetwork = mock<TekuP2PNetwork<Peer>>()
    val address = mock<PeerAddress>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(peer.address).thenReturn(address)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusFuture)
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(reputationManager.isConnectionInitiationAllowed(address)).thenReturn(true)
    whenever(p2pNetwork.peerCount).thenReturn(0)
    whenever(maruPeer.connectionInitiatedLocally()).thenReturn(true)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkIdHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig =
          SyncingConfig(
            syncTargetSelection = SyncingConfig.SyncTargetSelection.Highest,
            peerChainHeightPollingInterval = 1.seconds,
            elSyncStatusRefreshInterval = 1.seconds,
          ),
        reputationManager = reputationManager,
        isStaticPeer = { false },
      )
    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    manager.onConnect(peer)

    assertThat(manager.getPeer(nodeId)).isEqualTo(maruPeer)
    verify(maruPeerFactory).createMaruPeer(peer)
  }

  private fun addConnectedPeer(
    manager: MaruPeerManager,
    maruPeer: MaruPeer,
  ) {
    // Use reflection to add the existing peer to connectedPeers
    val connectedPeersField = MaruPeerManager::class.java.getDeclaredField("connectedPeers")
    connectedPeersField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val connectedPeers = connectedPeersField.get(manager) as ConcurrentHashMap<NodeId, MaruPeer>
    connectedPeers[maruPeer.id] = maruPeer
  }

  private fun addConnectedPeers(
    number: Int,
    manager: MaruPeerManager,
  ): List<MaruPeer> {
    val peers = mutableListOf<MaruPeer>()
    for (i in 1..number) {
      val nodeId = mock<NodeId>()
      val maruPeer = mock<MaruPeer>()

      whenever(maruPeer.id).thenReturn(nodeId)

      peers.add(maruPeer)
      addConnectedPeer(manager, maruPeer)
    }
    return peers
  }
}
