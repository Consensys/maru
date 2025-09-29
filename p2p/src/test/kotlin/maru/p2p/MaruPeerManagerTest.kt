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
import kotlin.time.Duration.Companion.seconds
import maru.config.P2PConfig
import maru.config.SyncingConfig
import maru.config.consensus.ElFork
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ConsensusConfig
import maru.consensus.ForkIdHashManager
import maru.consensus.ForkIdHashManagerImpl
import maru.consensus.ForkIdHasher
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import maru.core.ext.DataGenerators
import maru.crypto.Hashing
import maru.database.BeaconChain
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
import tech.pegasys.teku.networking.p2p.network.P2PNetwork as TekuP2PNetwork

class MaruPeerManagerTest {
  private object Fixtures {
    fun peerAddress(id: NodeId = mock()): PeerAddress = mock<PeerAddress>().also { whenever(it.id).thenReturn(id) }

    fun tekuPeer(
      id: NodeId = mock(),
      address: PeerAddress = peerAddress(id),
      initiatedLocally: Boolean = true,
    ): Peer =
      mock<Peer>().also {
        whenever(it.id).thenReturn(id)
        whenever(it.address).thenReturn(address)
        whenever(it.connectionInitiatedLocally()).thenReturn(initiatedLocally)
        whenever(it.connectionInitiatedRemotely()).thenReturn(!initiatedLocally)
      }

    fun maruPeer(
      initiatedLocally: Boolean = true,
      isConnected: Boolean = true,
      address: PeerAddress = mock(),
      awaitStatusFuture: SafeFuture<Status>? = statusFuture,
    ): MaruPeer {
      val id = address.getId() ?: mock<NodeId>()

      return mock<MaruPeer>().also {
        whenever(it.connectionInitiatedLocally()).thenReturn(initiatedLocally)
        whenever(it.isConnected).thenReturn(isConnected)
        whenever(it.address).thenReturn(address)
        whenever(it.getId()).thenReturn(id)
        awaitStatusFuture?.let { fut ->
          whenever(it.awaitInitialStatusAndCheckForkIdHash()).thenReturn(fut)
        }
      }
    }
  }

  companion object {
    private val chainId = 1337u
    private val beaconChain = InMemoryBeaconChain(DataGenerators.randomBeaconState(number = 0u, timestamp = 0u))
    val reputationManager =
      MaruReputationManager(
        metricsSystem = NoOpMetricsSystem(),
        timeProvider = SystemTimeProvider(),
        isStaticPeer = { _: NodeId -> false },
        reputationConfig = P2PConfig.Reputation(),
      )

    fun createForkIdHashManager(): ForkIdHashManager {
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

      return ForkIdHashManagerImpl(
        chainId = chainId,
        beaconChain = beaconChain,
        forksSchedule = forksSchedule,
        forkIdHasher = ForkIdHasher(ForkIdSerializer, Hashing::shortShaHash),
      )
    }

    val forkIdHashManager: ForkIdHashManager = createForkIdHashManager()

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
          forkIdHash = forkIdHashManager.currentHash(),
          latestBlockNumber = 0u,
          latestStateRoot = ByteArray(32) { 0x00 },
        ),
      )
  }

  @Test
  fun `does not schedule timeout when connection is initiated locally`() {
    val peer = Fixtures.tekuPeer(id = mock(), initiatedLocally = true)
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = Fixtures.maruPeer(initiatedLocally = true)
    val p2pConfig = mock<P2PConfig>()
    val syncConfig = mock<SyncingConfig>()

    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.connectionInitiatedLocally()).thenReturn(true)
    whenever(maruPeer.awaitInitialStatusAndCheckForkIdHash()).thenReturn(statusFuture)
    whenever(maruPeer.address).thenReturn(mock())
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig = syncConfig,
      )

    // Act
    manager.start(discoveryService = null, p2pNetwork = mock())
    manager.onConnect(peer)

    verify(maruPeerFactory).createMaruPeer(peer)
  }

  @Test
  fun `onConnect successfully connects peer when all conditions are met`() {
    val nodeId = mock<NodeId>()
    val peer = Fixtures.tekuPeer(nodeId, initiatedLocally = true)
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = Fixtures.maruPeer(initiatedLocally = true)
    val p2pConfig = mock<P2PConfig>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()
    val syncConfig = mock<SyncingConfig>()

    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.sendStatus()).thenReturn(SafeFuture.completedFuture(Unit))
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(maruPeer.awaitInitialStatusAndCheckForkIdHash()).thenReturn(statusFuture) // Uses correct fork ID hash
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig = syncConfig,
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
        reputationManager = reputationManager,
        isStaticPeer = { false },
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig = syncConfig,
      )

    val p2pNetwork = mock<P2PNetwork<Peer>>()
    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    // Add 5 connected peers to equal the maxPeers limit of 5
    addConnectedPeers(5, manager)

    val peer = Fixtures.tekuPeer(id = mock(), initiatedLocally = true)

    manager.onConnect(peer)

    verify(peer).disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
  }

  @Test
  fun `onConnect disconnects peer when too far behind during syncing`() {
    val nodeId = mock<NodeId>()
    val peer = Fixtures.tekuPeer(id = mock(), initiatedLocally = true)
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = Fixtures.maruPeer(initiatedLocally = true)
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
          forkIdHash = forkIdHashManager.currentHash(),
          latestBlockNumber = 900u, // 1000 (sync target) - 900 = 100 > 32 (blocksBehindThreshold)
          latestStateRoot = ByteArray(32) { 0x00 },
        ),
      )

    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(p2pNetwork.peerCount).thenReturn(5)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncingStatusProvider },
        syncConfig = syncConfig,
      )
    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    manager.onConnect(peer)

    verify(maruPeer).disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
    assertThat(manager.getPeer(nodeId)).isNull()
  }

  @Test
  fun `onConnect disconnects peer when behind and too many peers behind while synced`() {
    val nodeId = mock<NodeId>()
    val peer = Fixtures.tekuPeer(id = mock())
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = Fixtures.maruPeer()
    val p2pConfig = mock<P2PConfig>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()
    val syncConfig = mock<SyncingConfig>()

    // Create status with peer behind current head
    val currentHead = 100uL
    val statusBehind =
      SafeFuture.completedFuture(
        Status(
          forkIdHash = forkIdHashManager.currentHash(),
          latestBlockNumber = 50u, // Behind current head
          latestStateRoot = ByteArray(32) { 0x00 },
        ),
      )

    // Mock beacon chain to return current head
    val mockBeaconState = DataGenerators.randomBeaconState(number = currentHead, timestamp = 0uL)
    val mockBeaconChain = mock<BeaconChain>()
    whenever(mockBeaconChain.getLatestBeaconState()).thenReturn(mockBeaconState)

    whenever(p2pConfig.maxPeers).thenReturn(10) // maxPeers / 10 = 1, so 1 peer behind is too many
    whenever(p2pNetwork.peerCount).thenReturn(5)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        beaconChain = mockBeaconChain,
        syncConfig = syncConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
        syncStatusProviderProvider = { syncStatusProvider },
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
    val peer = Fixtures.tekuPeer(id = nodeId)
    val maruPeerFactory = mock<MaruPeerFactory>()
    val p2pConfig = mock<P2PConfig>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()
    val syncConfig = mock<SyncingConfig>()

    // Create a future that hasn't completed yet to simulate status exchange in progress
    val pendingStatusFuture = SafeFuture<Status>()
    val maruPeer = Fixtures.maruPeer(awaitStatusFuture = pendingStatusFuture, initiatedLocally = true)

    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(p2pNetwork.peerCount).thenReturn(5)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.awaitInitialStatusAndCheckForkIdHash()).thenReturn(pendingStatusFuture)
    whenever(syncConfig.desyncTolerance).thenReturn(32u)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig = syncConfig,
      )
    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    manager.onConnect(peer)

    // Verify peer is in status exchanging phase
    assertThat(manager.getPeer(nodeId)).isEqualTo(maruPeer)

    // Complete the status exchange successfully
    pendingStatusFuture.complete(
      Status(
        forkIdHash = forkIdHashManager.currentHash(),
        latestBlockNumber = 0u,
        latestStateRoot = ByteArray(32) { 0x00 },
      ),
    )

    // Verify peer is still available after successful status exchange
    assertThat(manager.getPeer(nodeId)).isEqualTo(maruPeer)
  }

  @Test
  fun `does not connect or add peer if reputation manager disallows connection`() {
    // Arrange
    val nodeId: NodeId = mock()
    val address = Fixtures.peerAddress(nodeId)
    val peer = Fixtures.tekuPeer(id = nodeId, address = address, initiatedLocally = true)
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = Fixtures.maruPeer(initiatedLocally = true)
    val p2pConfig = P2PConfig()
    val p2pNetwork = mock<TekuP2PNetwork<Peer>>()

    val reputationManager = mock<MaruReputationManager>()
    whenever(reputationManager.isConnectionInitiationAllowed(address)).thenReturn(false)

    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig =
          SyncingConfig(
            syncTargetSelection = SyncingConfig.SyncTargetSelection.Highest,
            peerChainHeightPollingInterval = 1.seconds,
            elSyncStatusRefreshInterval = 1.seconds,
          ),
      )

    // Act
    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    manager.onConnect(peer)

    // Assert
    verify(maruPeerFactory, never()).createMaruPeer(peer)
    assertThat(manager.getPeer(nodeId)).isNull()
  }

  @Test
  fun `connects and adds peer if reputation manager allows connection`() {
    // Arrange
    val nodeId: NodeId = mock()
    val address = Fixtures.peerAddress(nodeId)
    val peer = Fixtures.tekuPeer(id = nodeId, address = address, initiatedLocally = true)
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = Fixtures.maruPeer(initiatedLocally = true, isConnected = true)
    val p2pConfig = P2PConfig(maxPeers = 10)
    val reputationManager = mock<MaruReputationManager>()
    val p2pNetwork = mock<TekuP2PNetwork<Peer>>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(peer.address).thenReturn(address)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(reputationManager.isConnectionInitiationAllowed(address)).thenReturn(true)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        reputationManager = reputationManager,
        isStaticPeer = { false },
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig =
          SyncingConfig(
            syncTargetSelection = SyncingConfig.SyncTargetSelection.Highest,
            peerChainHeightPollingInterval = 1.seconds,
            elSyncStatusRefreshInterval = 1.seconds,
          ),
      )

    // Act
    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    manager.onConnect(peer)

    // Assert
    assertThat(manager.getPeer(nodeId)).isEqualTo(maruPeer)
    verify(maruPeerFactory).createMaruPeer(peer)
  }

  @Test
  fun `getPeers and peerCount only include actually connected peers`() {
    // Arrange
    val nodeId1: NodeId = mock()
    val nodeId2: NodeId = mock()

    val libp2pPeer1 = Fixtures.tekuPeer(id = nodeId1, initiatedLocally = true)
    val libp2pPeer2 = Fixtures.tekuPeer(id = nodeId2, initiatedLocally = true)

    val maruPeerFactory = mock<MaruPeerFactory>()
    val awaitStatusFuture =
      SafeFuture.completedFuture(
        Status(
          forkIdHash = forkIdHashManager.currentHash(),
          latestBlockNumber = 10u,
          latestStateRoot = ByteArray(32) { 0x00 },
        ),
      )
    val maruPeer1 =
      Fixtures.maruPeer(
        initiatedLocally = true,
        isConnected = true,
        awaitStatusFuture = awaitStatusFuture,
      )
    val maruPeer2 =
      Fixtures.maruPeer(
        initiatedLocally = true,
        isConnected = true,
        awaitStatusFuture = awaitStatusFuture,
      )
    val p2pConfig = P2PConfig()

    whenever(maruPeerFactory.createMaruPeer(libp2pPeer1)).thenReturn(maruPeer1)
    whenever(maruPeerFactory.createMaruPeer(libp2pPeer2)).thenReturn(maruPeer2)
    whenever(maruPeer1.sendStatus()).thenReturn(SafeFuture.completedFuture(Unit))
    whenever(maruPeer2.sendStatus()).thenReturn(SafeFuture.completedFuture(Unit))

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        reputationManager = reputationManager,
        isStaticPeer = { true },
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
        syncConfig =
          SyncingConfig(
            syncTargetSelection = SyncingConfig.SyncTargetSelection.Highest,
            peerChainHeightPollingInterval = 1.seconds,
            elSyncStatusRefreshInterval = 1.seconds,
          ),
      )

    // Act
    manager.start(discoveryService = null, p2pNetwork = mock())
    manager.onConnect(libp2pPeer1)
    manager.onConnect(libp2pPeer2)

    // Assert
    assertThat(manager.peerCount).isEqualTo(2)
    assertThat(manager.getPeers()).containsExactlyInAnyOrder(maruPeer1, maruPeer2)

    // Arrange 2
    whenever(maruPeer2.isConnected).thenReturn(false)

    // Assert 2
    assertThat(manager.peerCount).isEqualTo(1)
    assertThat(manager.getPeers()).containsExactly(maruPeer1)
    assertThat(manager.getPeer(nodeId2)).isNull()
    assertThat(manager.getPeer(nodeId1)).isEqualTo(maruPeer1)
  }

  private fun addConnectedPeer(
    manager: MaruPeerManager,
    maruPeer: MaruPeer,
  ) {
    // Use reflection to add the existing peer to connectedPeers
    val peersField = MaruPeerManager::class.java.getDeclaredField("peers")
    peersField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val peers = peersField.get(manager) as ConcurrentHashMap<NodeId, MaruPeer>
    peers[maruPeer.id] = maruPeer
  }

  private fun addConnectedPeers(
    number: Int,
    manager: MaruPeerManager,
  ): List<MaruPeer> {
    val peers = mutableListOf<MaruPeer>()
    for (i in 1..number) {
      val nodeId = mock<NodeId>()
      val address = Fixtures.peerAddress(nodeId)
      val maruPeer = Fixtures.maruPeer(address = address)
//      whenever(maruPeer.id).thenReturn(nodeId) // Explicitly stub address.id

      peers.add(maruPeer)
      addConnectedPeer(manager, maruPeer)
    }
    return peers
  }
}
