/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import maru.p2p.PeerHeadBlockProvider

/**
 * Responsible to keep track of peer's STATUS and select the head of the chain
 */
interface SyncTargetCalculator {
  fun calcTargetChaiHead(peerHeads: List<ULong>): ULong
}

fun interface SyncTargetUpdateHandler {
  fun onChainHeadUpdated(beaconBlockNumber: ULong)
}

/**
 * polls periodically peers chain head
 * and notify SyncTargetSelector when chain head of any peer changes
 *
 * it only notifies syncTargetUpdateHandler when there is actual update, not on every tick/calculation
 *
 * MaruPeerManager -> PeerChainTracker -> SyncController
 */
class PeerChainTracker(
  val peersHeadsProvider: PeerHeadBlockProvider,
  val syncTargetUpdateHandler: SyncTargetUpdateHandler,
  val targetChainHeadCalculator: SyncTargetCalculator,
)
