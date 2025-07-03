/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.api

import maru.p2p.Peer

interface NetworkDataProvider {
  fun getNodeId(): String

  fun getEnr(): String?

  fun getNodeAddresses(): List<String>

  fun getDiscoveryAddresses(): List<String>

  fun getPeers(): List<Peer>

  fun getPeer(peerId: String): Peer?
}
