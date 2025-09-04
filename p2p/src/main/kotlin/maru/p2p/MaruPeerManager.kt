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
import maru.p2p.discovery.MaruDiscoveryPeer
import maru.p2p.discovery.MaruDiscoveryService
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.networking.p2p.discovery.DiscoveryPeer
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNodeId
import tech.pegasys.teku.networking.p2p.libp2p.PeerAlreadyConnectedException
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
) : PeerHandler,
  PeerLookup {
  var scheduler: ScheduledExecutorService? = null

  private val log: Logger = LogManager.getLogger(this.javaClass)
  private val maxPeers = p2pConfig.maxPeers
  private val currentlySearching = AtomicBoolean(false)
  private val connectedPeers: ConcurrentHashMap<NodeId, MaruPeer> = ConcurrentHashMap()
  private var searchTaskFuture: ScheduledFuture<*>? = null
  private val connectionInProgress = mutableListOf<Bytes>()
  private var discoveryService: MaruDiscoveryService? = null

  private lateinit var p2pNetwork: P2PNetwork<Peer>

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
    if (isAStaticPeer || reputationManager.isExternalConnectionInitiationAllowed(peer.address)) {
      val maruPeer = maruPeerFactory.createMaruPeer(peer)
      connectedPeers[peer.id] = maruPeer
      if (maruPeer.connectionInitiatedLocally()) {
        maruPeer.sendStatus()
      } else {
        maruPeer.scheduleDisconnectIfStatusNotReceived(p2pConfig.statusUpdate.timeout)
      }
    } else {
      log.trace("Disconnecting from peer=${peer.address} due to connection not allowed yet.")
      peer.disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
    }
  }

  override fun onDisconnect(peer: Peer) {
    connectedPeers.remove(peer.id)
    log.trace("Peer={} disconnected", peer.id)
  }

  override fun getPeer(nodeId: NodeId): MaruPeer? = connectedPeers[nodeId]

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
}
