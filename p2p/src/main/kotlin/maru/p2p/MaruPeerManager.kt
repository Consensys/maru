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
import maru.p2p.messages.StatusMessageFactory
import tech.pegasys.teku.networking.p2p.network.PeerHandler
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer

class MaruPeerManager(
  private val statusMessageFactory: StatusMessageFactory,
  rpcProtocolIdGenerator: LineaRpcProtocolIdGenerator,
) : PeerHandler,
  PeerLookup {
  private val connectedPeers: ConcurrentHashMap<NodeId, MaruPeer> = ConcurrentHashMap()
  private val rpcMethods =
    RpcMethods(statusMessageFactory, rpcProtocolIdGenerator, this)

  override fun onConnect(peer: Peer) {
    val maruPeer =
      MaruPeerImpl(
        delegatePeer = peer,
        rpcMethods = rpcMethods,
        statusMessageFactory = statusMessageFactory,
      )
    connectedPeers.put(peer.id, maruPeer)
    if (maruPeer.connectionInitiatedLocally()) {
      maruPeer.sendStatus()
    } else {
      // TODO ensure that we have a status message from the peer
    }
  }

  override fun onDisconnect(peer: Peer) {
    connectedPeers.remove(peer.id)
  }

  override fun getPeer(nodeId: NodeId): MaruPeer? = connectedPeers.get(nodeId)

  fun getRpcMethods() = rpcMethods.all()
}
