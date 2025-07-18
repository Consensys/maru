/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import tech.pegasys.teku.networking.p2p.network.PeerHandler
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer

private const val STATUS_TIMEOUT_SECONDS = 10L

class MaruPeerManager(
  private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
  private val maruPeerFactory: MaruPeerFactory,
) : PeerHandler,
  PeerLookup {
  private val log: Logger = LogManager.getLogger(this::javaClass)
  private val connectedPeers: ConcurrentHashMap<NodeId, MaruPeer> = ConcurrentHashMap()

  init {
    scheduler.scheduleAtFixedRate({
      logConnectedPeers()
    }, 30, 30, TimeUnit.SECONDS)
  }

  private fun logConnectedPeers() {
    val peerIds = connectedPeers.keys.joinToString(", ") { it.toString() }
    log.info("Currently connected peers: [$peerIds]")
  }

  override fun onConnect(peer: Peer) {
    val maruPeer = maruPeerFactory.createMaruPeer(peer)
    connectedPeers.put(peer.id, maruPeer)
    if (maruPeer.connectionInitiatedLocally()) {
      maruPeer.sendStatus()
    } else {
      ensureStatusReceived(maruPeer)
    }
  }

  private fun ensureStatusReceived(peer: MaruPeer) {
    scheduler.schedule({
      if (peer.getStatus() == null) {
        peer.disconnectImmediately(
          Optional.of(DisconnectReason.REMOTE_FAULT),
          false,
        )
      }
    }, STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }

  override fun onDisconnect(peer: Peer) {
    connectedPeers.remove(peer.id)
  }

  override fun getPeer(nodeId: NodeId): MaruPeer? = connectedPeers[nodeId]

  override fun getPeers(): List<MaruPeer> = connectedPeers.values.toList()
}
