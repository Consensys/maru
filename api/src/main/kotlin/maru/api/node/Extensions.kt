/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.api.node

import maru.p2p.PeerInfo

fun PeerInfo.toPeerData(): PeerData =
  PeerData(
    peerId = nodeId,
    enr = enr,
    lastSeenP2PAddress = address,
    state = status.toString().lowercase(),
    direction = direction.toString().lowercase(),
  )
