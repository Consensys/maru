/*
   Copyright 2025 Consensys Software Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package maru.p2p

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import maru.config.P2P
import maru.p2p.discovery.MaruDiscoveryPeer
import maru.p2p.discovery.MaruDiscoveryService
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.network.PeerHandler
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.Peer

class MaruPeerManagerService(
  p2pConfig: P2P,
) : PeerHandler {
  private val log: Logger = LogManager.getLogger(this::class.java)

  private val maxPeers = p2pConfig.maxPeers
  private val currentlySearching = AtomicBoolean(false)
  private val connectionInProgress = mutableListOf<Bytes>()

  private lateinit var discoveryService: MaruDiscoveryService
  private lateinit var p2pNetwork: P2PNetwork<Peer>
  private var stopCalled = false

  fun start(
    discoveryService: MaruDiscoveryService,
    p2pNetwork: P2PNetwork<Peer>,
  ) {
    this.discoveryService = discoveryService
    this.p2pNetwork = p2pNetwork
    searchForPeersUntilMaxReached()
  }

  fun stop(): SafeFuture<Unit> {
    stopCalled = true
    return SafeFuture.completedFuture<Unit>(null)
  }

  private fun searchForPeersUntilMaxReached() {
    if (!stopCalled && currentlySearching.compareAndSet(false, true)) {
      discoveryService
        .searchForPeers()
        .orTimeout(Duration.ofSeconds(30L))
        .whenComplete { availablePeers, throwable ->
          log.debug("Finished searching for peers. Found {} peers.", availablePeers.size)
          if (!stopCalled) {
            if (throwable != null) {
              log.trace("Failed to discover peers: {}\n{}", throwable.message, throwable.stackTraceToString())
              discoveryService.getKnownPeers().forEach { peer ->
                tryToConnectIfNotFull(peer)
              }
            } else {
              availablePeers.forEach { peer ->
                tryToConnectIfNotFull(peer)
              }
            }
          }
        }.whenComplete { _, _ ->
          currentlySearching.set(false)
          log.debug("Peer count: {}. Max Peers: {}", p2pNetwork.peerCount, maxPeers)
          if (!stopCalled && p2pNetwork.peerCount < maxPeers) {
            CompletableFuture.runAsync { searchForPeersUntilMaxReached() }
          }
        }
    }
  }

  override fun onConnect(peer: Peer) {
    // TODO: here we could check if we want to be connected to that peer
    if (p2pNetwork.peerCount > maxPeers) {
      peer.disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
    }
  }

  override fun onDisconnect(peer: Peer) {
    if (!stopCalled && p2pNetwork.peerCount < maxPeers) {
      searchForPeersUntilMaxReached()
    }
  }

  private fun tryToConnectIfNotFull(peer: MaruDiscoveryPeer) {
    try {
      synchronized(connectionInProgress) {
        if (stopCalled) {
          return
        }
        if (p2pNetwork.peerCount >= maxPeers || connectionInProgress.contains(peer.nodeIdBytes)) {
          return
        }
        connectionInProgress.add(peer.nodeIdBytes)
      }

      log.debug("Peer {} Connecting to peer {}...", discoveryService.getLocalNodeRecord().nodeId, peer.nodeIdBytes)
      p2pNetwork
        .connect(p2pNetwork.createPeerAddress(peer))
        .orTimeout(30, TimeUnit.SECONDS)
        .whenComplete { _, throwable ->
          try {
            if (throwable != null) {
              log.debug("(1)Failed to connect to peer {}: {}", peer.nodeIdBytes, throwable.stackTraceToString())
            }
          } finally {
            synchronized(connectionInProgress) {
              connectionInProgress.remove(peer.nodeIdBytes)
            }
          }
        }
    } catch (e: Exception) {
      log.debug("(2)Failed to initiate connection to peer {}: {}", peer.nodeIdBytes, e.stackTraceToString())
      synchronized(connectionInProgress) {
        connectionInProgress.remove(peer.nodeIdBytes)
      }
    }
  }
}
