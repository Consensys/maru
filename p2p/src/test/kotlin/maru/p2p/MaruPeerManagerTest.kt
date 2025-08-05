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
import java.util.concurrent.ScheduledFuture
import maru.config.P2P
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
import maru.serialization.ForkIdSerializers
import maru.syncing.CLSyncStatus
import maru.syncing.ELSyncStatus
import maru.syncing.SyncStatusProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer

class MaruPeerManagerTest {
  companion object {
    private val chainId = 1337u
    private val beaconChain = InMemoryBeaconChain(DataGenerators.randomBeaconState(number = 0u, timestamp = 0u))

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

      return ForkIdHashProviderImpl(
        chainId = chainId,
        beaconChain = beaconChain,
        forksSchedule = forksSchedule,
        forkIdHasher = ForkIdHasher(ForkIdSerializers.ForkIdSerializer, Hashing::shortShaHash),
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

        override fun getSyncTarget(): ULong? = null
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
  fun `does not schedule timeout when connection is initiated locally`() {
    val mockScheduler = mock<ScheduledExecutorService>()
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2P>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(peer.connectionInitiatedLocally()).thenReturn(true)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.connectionInitiatedLocally()).thenReturn(true)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusFuture)
    whenever(p2pConfig.maxPeers).thenReturn(10)

    val manager =
      MaruPeerManager(
        mockScheduler,
        maruPeerFactory,
        p2pConfig,
        forkIdHashProvider,
        beaconChain,
        { syncStatusProvider },
      )
    manager.start(discoveryService = null, p2pNetwork = mock())
    manager.onConnect(peer)

    verify(mockScheduler, never()).schedule(any<Runnable>(), any(), any())
  }

  @Test
  fun `sends status message immediately for locally initiated connections`() {
    val mockScheduler = mock<ScheduledExecutorService>()
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2P>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.connectionInitiatedLocally()).thenReturn(true)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusFuture)
    whenever(p2pConfig.maxPeers).thenReturn(10)

    val manager =
      MaruPeerManager(
        mockScheduler,
        maruPeerFactory,
        p2pConfig,
        forkIdHashProvider,
        beaconChain,
        { syncStatusProvider },
      )
    manager.start(discoveryService = null, p2pNetwork = mock())
    manager.onConnect(peer)

    verify(mockScheduler, never()).schedule(any<Runnable>(), any(), any())
  }

  @Test
  fun `does not send status message for remotely initiated connections`() {
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<DefaultMaruPeer>()
    val p2pConfig = mock<P2P>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.connectionInitiatedLocally()).thenReturn(false)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusFuture)
    whenever(maruPeer.getStatus()).thenReturn(null)
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(p2pConfig.statusUpdate).thenReturn(P2P.StatusUpdateConfig())

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkidHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
      )
    manager.start(discoveryService = null, p2pNetwork = mock())
    manager.onConnect(peer)

    verify(maruPeer, never()).sendStatus()
  }

  @Test
  fun `creates maru peer through factory when peer connects`() {
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2P>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.connectionInitiatedLocally()).thenReturn(true)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusFuture)
    whenever(p2pConfig.maxPeers).thenReturn(10)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkidHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
      )
    manager.start(discoveryService = null, p2pNetwork = mock())
    manager.onConnect(peer)

    verify(maruPeerFactory).createMaruPeer(peer)
  }

  @Test
  fun `stores connected peer in manager for retrieval`() {
    val mockScheduler = mock<ScheduledExecutorService>()
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()

    val p2pConfig = mock<P2P>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.connectionInitiatedLocally()).thenReturn(false)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusFuture)
    whenever(maruPeer.getStatus()).thenReturn(null)
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(p2pConfig.statusUpdate).thenReturn(P2P.StatusUpdateConfig())
    doReturn(mock<ScheduledFuture<*>>()).whenever(mockScheduler).schedule(any<Runnable>(), any(), any())

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkidHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
      )
    manager.start(discoveryService = null, p2pNetwork = mock())
    manager.onConnect(peer)

    assertThat(manager.getPeer(nodeId)).isEqualTo(maruPeer)
  }

  @Test
  fun `onConnect successfully connects peer when all conditions are met`() {
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2P>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusFuture) // Uses correct fork ID hash

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkidHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
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
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val p2pConfig = mock<P2P>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(p2pConfig.maxPeers).thenReturn(5)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkidHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
      )

    // Add 6 connected peers to exceed the maxPeers limit of 5
    addConnectedPeers(5, manager)

    manager.start(discoveryService = null, p2pNetwork = p2pNetwork)
    manager.onConnect(peer)

    verify(peer).disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
    verify(maruPeerFactory, never()).createMaruPeer(any())
  }

  @Test
  fun `onConnect disconnects peer when fork ID hash mismatch`() {
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2P>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()

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
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(p2pNetwork.peerCount).thenReturn(5)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusWithDifferentForkId)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkidHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
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
    val p2pConfig = mock<P2P>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()

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

        override fun getSyncTarget(): ULong? = 1000u // Target block number
      }

    // Create status with peer far behind (target - peer > blocksBehindThreshold)
    val statusFarBehind =
      SafeFuture.completedFuture(
        Status(
          forkIdHash = forkIdHashProvider.currentForkIdHash(),
          latestBlockNumber = 900u, // 1000 - 900 = 100 > 32 (blocksBehindThreshold)
          latestStateRoot = ByteArray(32) { 0x00 },
        ),
      )

    whenever(peer.id).thenReturn(nodeId)
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(p2pNetwork.peerCount).thenReturn(5)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusFarBehind)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkidHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncingStatusProvider },
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
    val p2pConfig = mock<P2P>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()

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
    whenever(p2pConfig.maxPeers).thenReturn(10) // maxPeers / 10 = 1, so 1 peer behind is too many
    whenever(p2pNetwork.peerCount).thenReturn(5)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(statusBehind)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkidHashProvider = forkIdHashProvider,
        beaconChain = mockBeaconChain,
        syncStatusProviderProvider = { syncStatusProvider }, // SYNCED status
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

  private fun addConnectedPeer(
    manager: MaruPeerManager,
    maruPeer: MaruPeer,
  ) {
    // Use reflection to add the existing peer to connectedPeers
    val connectedPeersField = MaruPeerManager::class.java.getDeclaredField("connectedPeers")
    connectedPeersField.isAccessible = true
    val connectedPeers = connectedPeersField.get(manager) as ConcurrentHashMap<NodeId, MaruPeer>
    connectedPeers[maruPeer.id] = maruPeer
  }

  private fun addConnectedPeers(
    number: Int,
    manager: MaruPeerManager,
  ): List<MaruPeer> {
    val peers = mutableListOf<MaruPeer>()
    for (i in 1..number) {
      val peer = mock<MaruPeer>()
      val nodeId = mock<NodeId>()
      whenever(peer.id).thenReturn(nodeId)
      val status = mock<Status>()
      whenever(status.latestBlockNumber).thenReturn(i.toULong())
      whenever(peer.getStatus()).thenReturn(status)
      peers.add(peer)
      addConnectedPeer(manager, peer)
    }
    return peers
  }

  @Test
  fun `onConnect handles peer during status exchange phase`() {
    val nodeId = mock<NodeId>()
    val peer = mock<Peer>()
    val maruPeerFactory = mock<MaruPeerFactory>()
    val maruPeer = mock<MaruPeer>()
    val p2pConfig = mock<P2P>()
    val p2pNetwork = mock<P2PNetwork<Peer>>()

    // Create a future that hasn't completed yet to simulate status exchange in progress
    val pendingStatusFuture = SafeFuture<Status>()

    whenever(peer.id).thenReturn(nodeId)
    whenever(p2pConfig.maxPeers).thenReturn(10)
    whenever(p2pNetwork.peerCount).thenReturn(5)
    whenever(maruPeerFactory.createMaruPeer(peer)).thenReturn(maruPeer)
    whenever(maruPeer.awaitInitialStatus()).thenReturn(pendingStatusFuture)

    val manager =
      MaruPeerManager(
        maruPeerFactory = maruPeerFactory,
        p2pConfig = p2pConfig,
        forkidHashProvider = forkIdHashProvider,
        beaconChain = beaconChain,
        syncStatusProviderProvider = { syncStatusProvider },
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
}
