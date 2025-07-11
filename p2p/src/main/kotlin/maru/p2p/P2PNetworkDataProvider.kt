/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import maru.api.NetworkDataProvider

class P2PNetworkDataProvider(
  val p2PNetwork: P2PNetwork,
) : NetworkDataProvider {
  override fun getNodeId(): String = p2PNetwork.nodeId

  override fun getEnr(): String? = p2PNetwork.enr

  override fun getNodeAddresses(): List<String> = p2PNetwork.nodeAddresses

  override fun getDiscoveryAddresses(): List<String> = p2PNetwork.discoveryAddresses

  override fun getPeers(): List<Peer> = p2PNetwork.getPeers()

  override fun getPeer(peerId: String): Peer? = p2PNetwork.getPeer(peerId)
}
