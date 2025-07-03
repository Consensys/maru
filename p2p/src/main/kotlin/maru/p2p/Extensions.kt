/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import tech.pegasys.teku.networking.p2p.peer.Peer as TekuPeer

fun TekuPeer.toMaruPeer(): Peer =
  Peer(
    nodeId = id.toBase58(),
    enr = null,
    address = address.toExternalForm(),
    status = if (isConnected) Peer.PeerStatus.CONNECTED else Peer.PeerStatus.DISCONNECTED,
    direction =
      if (connectionInitiatedLocally()) {
        Peer.PeerDirection.OUTBOUND
      } else {
        Peer.PeerDirection.INBOUND
      },
  )
