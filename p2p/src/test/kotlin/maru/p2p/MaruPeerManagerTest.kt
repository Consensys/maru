/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import maru.config.P2P
import maru.config.consensus.ElFork
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ConsensusConfig
import maru.consensus.ForkIdHashProvider
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

      return ForkIdHashProvider(
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
}
