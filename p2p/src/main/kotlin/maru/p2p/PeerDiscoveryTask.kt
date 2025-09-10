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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import maru.p2p.discovery.MaruDiscoveryPeer
import maru.p2p.discovery.MaruDiscoveryService
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.networking.p2p.libp2p.PeerAlreadyConnectedException
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.peer.Peer
import tech.pegasys.teku.networking.p2p.reputation.ReputationManager

class PeerDiscoveryTask(
  private val discoveryService: MaruDiscoveryService,
  private val p2pNetwork: P2PNetwork<Peer>,
  private val reputationManager: ReputationManager,
  private val maxPeers: Int,
  private val getPeerCount: () -> Int,
) {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  private val currentlySearching = AtomicBoolean(false)
  private var discoveryTaskFuture: ScheduledFuture<*>? = null
  private val started = AtomicBoolean(false)
  private val connectionInProgress = mutableListOf<Bytes>()
  private val scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory())

  fun start() {
    if (!started.compareAndSet(false, true)) {
      log.warn("Trying to start already started PeerDiscoveryTask")
      return
    }
    discoveryTaskFuture =
      scheduler.scheduleWithFixedDelay(
        { runSearchTask(discoveryService) },
        0,
        1000,
        TimeUnit.MILLISECONDS,
      )
  }

  fun stop() {
    if (!started.compareAndSet(true, false)) {
      log.warn("Trying to stop already stopped PeerDiscoveryTask")
      return
    }
    discoveryTaskFuture!!.cancel(true)
    discoveryTaskFuture = null
  }

  private fun runSearchTask(discoveryService: MaruDiscoveryService) {
    if (getPeerCount() >= maxPeers) return
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
              availablePeers,
              getPeerCount,
            )
            availablePeers.forEach { peer -> tryToConnect(peer) }
          }
        }
    } finally {
      currentlySearching.set(false)
    }
  }

  private fun tryToConnect(peer: MaruDiscoveryPeer) {
    try {
      if (!started.get()) return
      if (getPeerCount() >= maxPeers) return

      val peerAddress = p2pNetwork.createPeerAddress(peer)

      if (p2pNetwork.isConnected(peerAddress)) return
      if (!reputationManager.isConnectionInitiationAllowed(peerAddress)) return

      synchronized(connectionInProgress) {
        if (connectionInProgress.contains(peer.nodeId)) {
          return
        }
        connectionInProgress.add(peer.nodeId)
      }

      // if the p2pNetwork.connect() is successful, the onConnect() method on MaruPeerManager will be called
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
}
