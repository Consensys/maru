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
import io.libp2p.crypto.keys.unmarshalSecp256k1PublicKey
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import maru.config.P2PConfig
import maru.config.SyncingConfig
import maru.consensus.ForkIdHashProvider
import maru.database.BeaconChain
import maru.p2p.discovery.MaruDiscoveryPeer
import maru.p2p.discovery.MaruDiscoveryService
import maru.syncing.CLSyncStatus
import maru.syncing.SyncStatusProvider
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.networking.p2p.discovery.DiscoveryPeer
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNodeId
import tech.pegasys.teku.networking.p2p.libp2p.PeerManager
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.network.PeerAddress
import tech.pegasys.teku.networking.p2p.network.PeerHandler
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer

class MaruPeerManager(
  private val maruPeerFactory: MaruPeerFactory,
  p2pConfig: P2PConfig,
  private val forkIdHashProvider: ForkIdHashProvider,
  private val beaconChain: BeaconChain,
  private val syncStatusProviderProvider: () -> SyncStatusProvider,
  syncConfig: SyncingConfig,
) : PeerHandler,
  PeerLookup {
  var scheduler: ScheduledExecutorService? = null

  val blocksBehindThreshold: ULong = syncConfig.desyncTolerance

  private val log: Logger = LogManager.getLogger(this.javaClass)
  private val maxPeers = p2pConfig.maxPeers
  private val currentlySearching = AtomicBoolean(false)
  private val connectedPeers: ConcurrentHashMap<NodeId, MaruPeer> = ConcurrentHashMap()
  val peerCount: Int
    get() = connectedPeers.size

  private val connectionInProgress = mutableListOf<Bytes>()
  private var discoveryService: MaruDiscoveryService? = null
  private val statusExchangingMaruPeers = mutableMapOf<NodeId, MaruPeer>()
  private val maxUnsyncedPeers = p2pConfig.maxUnsyncedPeers

  private lateinit var p2pNetwork: P2PNetwork<Peer>
  private lateinit var peerManager: PeerManager
  private lateinit var syncStatusProvider: SyncStatusProvider

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
    if (scheduler == null) {
      scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory())
    }
    scheduler!!.scheduleAtFixedRate({
      logConnectedPeers()
    }, 20000, 20000, TimeUnit.MILLISECONDS)
    searchForPeersUntilMaxReached()
  }

  fun stop(): SafeFuture<Unit> {
    if (!started.compareAndSet(true, false)) {
      log.warn("Trying to stop stopped MaruPeerManager")
      return SafeFuture.completedFuture(Unit)
    }
    scheduler!!.shutdown()
    scheduler = null
    return SafeFuture.completedFuture(Unit)
  }

  private fun searchForPeersUntilMaxReached() {
    if (started.get() && currentlySearching.compareAndSet(false, true)) {
      discoveryService?.let { discoveryService ->
        discoveryService
          .searchForPeers()
          .orTimeout(Duration.ofSeconds(30L))
          .whenComplete { availablePeers, throwable ->
            log.trace("Finished searching for peers. Found {} peers.", availablePeers.size)
            if (started.get()) {
              if (throwable != null) {
                log.debug("Failed to discover peers: {}", throwable.message, throwable)
                discoveryService.getKnownPeers()
              } else {
                availablePeers
              }.forEach { peer ->
                tryToConnectIfNotFull(peer)
              }
            }
          }.whenComplete { _, _ ->
            currentlySearching.set(false)
            log.trace("peerCount={}. maxPeers={}", peerCount, maxPeers)
            if (started.get() && peerCount < maxPeers) {
              scheduler!!.schedule({ searchForPeersUntilMaxReached() }, 1, TimeUnit.SECONDS)
            }
          }
      }
    }
  }

  private fun logConnectedPeers() {
    log.debug("Currently connected peers={}", connectedPeers.keys.toList())
    discoveryService?.getKnownPeers()?.forEach { peer ->
      log.info("discovered peer={}", peer)
    }
  }

  override fun onConnect(peer: Peer) {
    // TODO: here we could check if we want to be connected to that peer
    if (peerCount > maxPeers) {
      // TODO: We could disconnect another peer here, based on some criteria
      peer.disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
      return
    }
    val maruPeer = maruPeerFactory.createMaruPeer(peer)
    val localBeaconChainHead = beaconChain.getLatestBeaconState().beaconBlockHeader.number
    val targetBlockNumber =
      syncStatusProvider.getSyncTarget() ?: localBeaconChainHead
    statusExchangingMaruPeers[peer.id] = maruPeer
    maruPeer
      .awaitInitialStatus()
      .orTimeout(Duration.ofSeconds(15L))
      .thenApply { status ->
        if (!status.forkIdHash.contentEquals(forkIdHashProvider.currentForkIdHash())) {
          log.debug(
            "Peer={} has a different forkIdHash={} than expected={}. Disconnecting.",
            peer.id,
            status.forkIdHash,
            forkIdHashProvider.currentForkIdHash(),
          )
          maruPeer.disconnectCleanly(DisconnectReason.IRRELEVANT_NETWORK)
        } else if (syncStatusProvider.getCLSyncStatus() == CLSyncStatus.SYNCING &&
          status.latestBlockNumber + blocksBehindThreshold < targetBlockNumber
        ) {
          log.debug(
            "Peer={} is too far behind our target block number={} (peer's block number={}). Disconnecting.",
            peer.id,
            syncStatusProvider.getSyncTarget(),
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
          connectedPeers.put(peer.id, maruPeer)
          log.trace("Connected to peer={} with status={}", peer.id, status)
        }
      }.always {
        statusExchangingMaruPeers.remove(peer.id)
      }
  }

  private fun tooManyPeersBehind(currentHead: ULong): Boolean =
    connectedPeers.values.count { peer ->
      // peers in connectedPeers always have a status
      peer.getStatus()!!.latestBlockNumber + blocksBehindThreshold < currentHead
    } >= maxUnsyncedPeers

  override fun onDisconnect(peer: Peer) {
    connectedPeers.remove(peer.id)
    log.trace("Peer={} disconnected", peer.id)
    if (started.get() && peerCount < maxPeers) {
      searchForPeersUntilMaxReached()
    }
  }

  override fun getPeer(nodeId: NodeId): MaruPeer? {
    if (connectedPeers.containsKey(nodeId)) {
      return connectedPeers[nodeId]
    } else if (statusExchangingMaruPeers.containsKey(nodeId)) {
      return statusExchangingMaruPeers[nodeId]
    }
    return null
  }

  override fun getPeers(): List<MaruPeer> = connectedPeers.values.toList()

  private fun tryToConnectIfNotFull(peer: MaruDiscoveryPeer) {
    try {
      val peerId = getNodeId(peer)
      if (!started.get() || p2pNetwork.isConnected(PeerAddress(peerId))) {
        return
      }
      synchronized(connectionInProgress) {
        if (peerCount >= maxPeers || connectionInProgress.contains(peer.nodeId)) {
          return
        }
        connectionInProgress.add(peer.nodeId)
      }

      p2pNetwork
        .connect(p2pNetwork.createPeerAddress(peer))
        .orTimeout(30, TimeUnit.SECONDS)
        .whenComplete { _, throwable ->
          try {
            if (throwable != null) {
              log.error("Failed to connect to peer={}", peer.nodeId, throwable)
            }
          } finally {
            synchronized(connectionInProgress) {
              connectionInProgress.remove(peer.nodeId)
            }
          }
        }
    } catch (e: Exception) {
      log.debug("Failed to initiate connection to peer={}. errorMessage={}", peer.nodeId, e.message, e)
      synchronized(connectionInProgress) {
        connectionInProgress.remove(peer.nodeId)
      }
    }
  }

  fun getNodeId(peer: DiscoveryPeer): LibP2PNodeId {
    val pubKey = unmarshalSecp256k1PublicKey(peer.publicKey.toArrayUnsafe())
    return LibP2PNodeId(PeerId.fromPubKey(pubKey))
  }

  fun setPeerManager(peerManager: PeerManager) {
    this.peerManager = peerManager
  }
}
