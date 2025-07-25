/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.discovery

import java.net.InetSocketAddress
import java.util.Optional
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.networking.p2p.discovery.DiscoveryPeer

class MaruDiscoveryPeer(
  publicKeyBytes: Bytes?,
  val nodeIdBytes: Bytes?,
  val addr: InetSocketAddress?,
  val forkIdBytes: Optional<Bytes>,
) : DiscoveryPeer(publicKeyBytes, nodeIdBytes, addr, null, null, null) {
  override fun toString(): String = "MaruDiscoveryPeer(nodeId=$nodeIdBytes, address=$addr, maruForkId=$forkIdBytes)"
}
