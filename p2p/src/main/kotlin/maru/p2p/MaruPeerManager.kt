/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import maru.config.P2PConfig
import maru.config.SyncingConfig
import maru.database.BeaconChain
import maru.p2p.discovery.MaruDiscoveryService
import maru.syncing.CLSyncStatus
import maru.syncing.SyncStatusProvider
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.network.PeerHandler
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer

class MaruPeerManager(
  private val maruPeerFactory: MaruPeerFactory,
  p2pConfig: P2PConfig,
  private val reputationManager: MaruReputationManager,
  private val isStaticPeer: (NodeId) -> Boolean,
  private val beaconChain: BeaconChain,
  private val syncStatusProviderProvider: () -> SyncStatusProvider,
  syncConfig: SyncingConfig,
) : PeerHandler,
  PeerLookup {
  val blocksBehindThreshold: ULong = syncConfig.desyncTolerance

  private val log: Logger = LogManager.getLogger(this.javaClass)

  private val maxPeers = p2pConfig.maxPeers
  private val maxUnsyncedPeers = p2pConfig.maxUnsyncedPeers

  private var discoveryService: MaruDiscoveryService? = null
  private var discoveryTask: PeerDiscoveryTask? = null

  private val peers: ConcurrentHashMap<NodeId, MaruPeer> = ConcurrentHashMap()
  private val statusExchangingMaruPeers = mutableMapOf<NodeId, MaruPeer>()

  private var scheduler: ScheduledExecutorService? = null
  private lateinit var p2pNetwork: P2PNetwork<Peer>
  private lateinit var syncStatusProvider: SyncStatusProvider

  val peerCount: Int
    get() = connectedPeers().size

  @Volatile
  private var started = AtomicBoolean(false)

  fun start(
    discoveryService: MaruDiscoveryService?,
    p2pNetwork: P2PNetwork<Peer>,
  ) {
    if (!started.compareAndSet(false, true)) {
      log.warn("Trying to start already started MaruPeerManager")
      return
    }
    this.discoveryService = discoveryService
    this.p2pNetwork = p2pNetwork
    this.syncStatusProvider = syncStatusProviderProvider()
    scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory())
    scheduler!!.scheduleAtFixedRate({
      logConnectedPeers()
    }, 20000, 20000, TimeUnit.MILLISECONDS)
    if (discoveryService != null) {
      discoveryTask =
        PeerDiscoveryTask(
          discoveryService = discoveryService,
          p2pNetwork = p2pNetwork,
          reputationManager = reputationManager,
          maxPeers = maxPeers,
          getPeerCount = { peerCount },
        )
      discoveryTask!!.start()
    }
  }

  fun stop(): SafeFuture<Unit> {
    if (!started.compareAndSet(true, false)) {
      log.warn("Trying to stop stopped MaruPeerManager")
      return SafeFuture.completedFuture(Unit)
    }
    discoveryTask?.stop()
    discoveryTask = null
    scheduler!!.shutdown()
    scheduler = null
    return SafeFuture.completedFuture(Unit)
  }

  private fun logConnectedPeers() {
    log.info("Currently connected peers={}", connectedPeers().keys.toList())
    if (log.isDebugEnabled) {
      discoveryService?.getKnownPeers()?.forEach { peer ->
        log.debug("discovered peer={}", peer)
      }
    }
  }

  override fun onConnect(peer: Peer) {
    val isAStaticPeer = isStaticPeer(peer.id)
    if (!isAStaticPeer && peerCount >= maxPeers) {
      peer.disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
      return
    }
    if (isAStaticPeer || reputationManager.isConnectionInitiationAllowed(peer.address)) {
      val maruPeer = maruPeerFactory.createMaruPeer(peer)
      val localBeaconChainHead = beaconChain.getLatestBeaconState().beaconBlockHeader.number
      statusExchangingMaruPeers[peer.id] = maruPeer
      maruPeer
        .awaitInitialStatusAndCheckForkIdHash()
        .orTimeout(Duration.ofSeconds(15L))
        .whenComplete { status, throwable ->
          if (throwable != null) {
            log.debug("Failed to get status from peer={}", peer.id, throwable)
          } else {
            if (syncStatusProvider.getCLSyncStatus() == CLSyncStatus.SYNCING &&
              status.latestBlockNumber + blocksBehindThreshold < syncStatusProvider.getCLSyncTarget()
            ) {
              log.debug(
                "Peer={} is too far behind our target block number={} (peer's block number={}). Disconnecting.",
                peer.id,
                syncStatusProvider.getCLSyncTarget(),
                status.latestBlockNumber,
              )
              maruPeer.disconnectCleanly(DisconnectReason.TOO_MANY_PEERS) // there is no better reason available
            } else if (syncStatusProvider.getCLSyncStatus() == CLSyncStatus.SYNCED &&
              status.latestBlockNumber + blocksBehindThreshold < localBeaconChainHead &&
              tooManyPeersBehind(localBeaconChainHead)
            ) {
              log.debug("Peer={} is behind and we already have too many peers behind. Disconnecting.", peer.id)
              maruPeer.disconnectCleanly(DisconnectReason.TOO_MANY_PEERS) // there is no better reason available
            } else {
              peers[peer.id] = maruPeer
              log.debug("Connected to peer={} with status={}", peer.id, status)
            }
          }
        }.always {
          statusExchangingMaruPeers.remove(peer.id)
        }
    } else {
      log.debug("Disconnecting from peer={} because connection is not allowed yet.", peer.address)
      peer.disconnectCleanly(DisconnectReason.RATE_LIMITING)
    }
  }

  private fun tooManyPeersBehind(currentHead: ULong): Boolean =
    peers.values.count { peer ->
      // peers in connectedPeers always have a status
      peer.getStatus()!!.latestBlockNumber + blocksBehindThreshold < currentHead
    } >= maxUnsyncedPeers

  private fun connectedPeers(): Map<NodeId, MaruPeer> = peers.filter { it.value.isConnected }

  override fun onDisconnect(peer: Peer) {
    peers.remove(peer.id)
    log.debug("Peer={} disconnected", peer.id)
  }

  override fun getPeer(nodeId: NodeId): MaruPeer? {
    if (connectedPeers().containsKey(nodeId)) {
      return peers[nodeId]
    } else if (statusExchangingMaruPeers.containsKey(nodeId)) {
      return statusExchangingMaruPeers[nodeId]
    }
    return null
  }

  override fun getPeers(): List<MaruPeer> = connectedPeers().values.toList()
}
