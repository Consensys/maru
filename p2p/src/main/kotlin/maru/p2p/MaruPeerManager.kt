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
import java.util.concurrent.ScheduledFuture
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
import tech.pegasys.teku.networking.p2p.libp2p.PeerAlreadyConnectedException
import tech.pegasys.teku.networking.p2p.libp2p.PeerManager
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.network.PeerHandler
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer

class MaruPeerManager(
  private val maruPeerFactory: MaruPeerFactory,
  private val p2pConfig: P2PConfig,
  private val reputationManager: MaruReputationManager,
  private val isStaticPeer: (NodeId) -> Boolean,
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
  private var searchTaskFuture: ScheduledFuture<*>? = null
  private val connectionInProgress = mutableListOf<Bytes>()
  private var discoveryService: MaruDiscoveryService? = null
  private val statusExchangingMaruPeers = mutableMapOf<NodeId, MaruPeer>()
  private val maxUnsyncedPeers = p2pConfig.maxUnsyncedPeers

  private lateinit var p2pNetwork: P2PNetwork<Peer>
  private lateinit var peerManager: PeerManager
  private lateinit var syncStatusProvider: SyncStatusProvider

  val peerCount: Int
    get() = connectedPeers.size

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
    if (discoveryService != null) {
      searchTaskFuture =
        scheduler!!.scheduleWithFixedDelay(
          { runSearchTask(discoveryService) },
          0,
          1000,
          TimeUnit.MILLISECONDS,
        )
    }
  }

  fun stop(): SafeFuture<Unit> {
    if (!started.compareAndSet(true, false)) {
      log.warn("Trying to stop stopped MaruPeerManager")
      return SafeFuture.completedFuture(Unit)
    }
    searchTaskFuture?.cancel(true)
    searchTaskFuture = null
    scheduler!!.shutdown()
    scheduler = null
    return SafeFuture.completedFuture(Unit)
  }

  private fun runSearchTask(discoveryService: MaruDiscoveryService) {
    if (peerCount >= maxPeers) return
    if (!started.get()) return
    if (!currentlySearching.compareAndSet(false, true)) return

    try {
      discoveryService
        .searchForPeers()
        .orTimeout(Duration.ofSeconds(30L))
        .whenComplete { availablePeers, throwable ->
          if (throwable != null) {
            log.trace("Finished searching for peers with error.")
          } else {
            log.trace(
              "Finished searching for peers. Found {} peers. Currently connected to {} peers.",
              availablePeers.size,
              peerCount,
            )
            availablePeers.forEach { peer -> tryToConnect(peer) }
          }
        }
    } finally {
      currentlySearching.set(false)
    }
  }

  private fun logConnectedPeers() {
    log.info("Currently connected peers={}", connectedPeers.keys.toList())
    if (log.isDebugEnabled) {
      discoveryService?.getKnownPeers()?.forEach { peer ->
        log.debug("discovered peer={}", peer)
      }
    }
  }

  override fun onConnect(peer: Peer) {
    // TODO: here we could check if we want to be connected to that peer
    val isAStaticPeer = isStaticPeer(peer.id)
    if (!isAStaticPeer && peerCount >= maxPeers) {
      // TODO: We could disconnect another peer here, based on some criteria
      peer.disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
      return
    }
    if (isAStaticPeer || reputationManager.isConnectionInitiationAllowed(peer.address)) {
      val maruPeer = maruPeerFactory.createMaruPeer(peer)
      val localBeaconChainHead = beaconChain.getLatestBeaconState().beaconBlockHeader.number
      val targetBlockNumber = syncStatusProvider.getCLSyncTarget()
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
            connectedPeers.put(peer.id, maruPeer)
            log.trace("Connected to peer={} with status={}", peer.id, status)
          }
        }.always {
          statusExchangingMaruPeers.remove(peer.id)
        }
    } else {
      log.trace("Disconnecting from peer=${peer.address} due to connection not allowed yet.")
      peer.disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
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

  private fun tryToConnect(peer: MaruDiscoveryPeer) {
    try {
      if (!started.get()) return
      if (peerCount >= maxPeers) return

      val peerAddress = p2pNetwork.createPeerAddress(peer)

      if (p2pNetwork.isConnected(peerAddress)) return
      if (!reputationManager.isConnectionInitiationAllowed(peerAddress)) return

      synchronized(connectionInProgress) {
        if (connectionInProgress.contains(peer.nodeId)) {
          return
        }
        connectionInProgress.add(peer.nodeId)
      }

      p2pNetwork
        .connect(peerAddress)
        .orTimeout(30, TimeUnit.SECONDS)
        .whenComplete { _, throwable ->
          try {
            if (throwable != null) {
              if (throwable.cause !is PeerAlreadyConnectedException) {
                reputationManager.reportInitiatedConnectionFailed(peerAddress)
                log.trace("Failed to connect to peer={}", peerAddress, throwable)
              } else {
                log.trace("Peer is already connected, peer={}", peerAddress)
              }
            } else {
              log.trace("Successfully connected to peer={}", peerAddress)
            }
          } finally {
            synchronized(connectionInProgress) {
              connectionInProgress.remove(peer.nodeId)
            }
          }
        }
    } catch (e: Exception) {
      log.trace("Failed to initiate connection to peer={}. errorMessage={}", peer.nodeId, e.message, e)
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
