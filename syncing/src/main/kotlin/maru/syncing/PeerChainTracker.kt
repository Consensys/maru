/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import java.util.Timer
import kotlin.concurrent.timerTask
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import maru.p2p.PeersHeadBlockProvider
import maru.services.LongRunningService

/**
 * polls periodically peers chain head
 * and notify SyncTargetSelector when chain head of any peer changes
 *
 * it only notifies syncTargetUpdateHandler when there is actual update, not on every tick/calculation
 *
 * MaruPeerManager -> PeerChainTracker -> SyncController
 */
class PeerChainTracker(
  private val peersHeadsProvider: PeersHeadBlockProvider,
  private val syncTargetUpdateHandler: SyncTargetUpdateHandler,
  private val targetChainHeadCalculator: SyncTargetSelector,
  private val config: Config,
  timerFactory: (String, Boolean) -> Timer = { name, isDaemon -> Timer(name, isDaemon) },
) : LongRunningService {
  data class Config(
    val pollingUpdateInterval: Duration,
    val granularity: UInt, // Resolution of the peer heights
  ) {
    init {
      require(granularity > 0U) { "Granularity should not be 0!" }
    }
  }

  private var peers = mutableMapOf<String, ULong>()
  private var lastNotifiedTarget: ULong = 0UL // 0 is an Ok magic number, since it represents Genesis

  private val poller = timerFactory("peer-chain-tracker", true)

  /**
   * Rounds the block height according to the configured granularity
   */
  private fun roundHeight(height: ULong): ULong {
    if (config.granularity <= 1u) return height

    return (height / config.granularity.toULong()) * config.granularity.toULong()
  }

  /**
   * Updates the peer view and triggers sync target updates if needed
   */
  private fun updatePeerView() {
    val newPeerHeads = peersHeadsProvider.getPeersHeads()
    val roundedNewPeerHeads = newPeerHeads.mapValues { roundHeight(it.value) }
    val hasActualChanges = hasActualChanges(roundedNewPeerHeads)
    peers = roundedNewPeerHeads.toMutableMap()
    // If there are changes, update the state and recalculate the sync target
    if (hasActualChanges && peers.isNotEmpty()) {
      val newSyncTarget = targetChainHeadCalculator.selectBestSyncTarget(peers.values.toList())
      if (newSyncTarget != lastNotifiedTarget) { // Only send an update if there's an actual target change
        syncTargetUpdateHandler.onChainHeadUpdated(newSyncTarget)
        lastNotifiedTarget = newSyncTarget
      }
    }
  }

  /**
   * Checks if there are actual changes compared to current peer view
   */
  private fun hasActualChanges(newPeerHeads: Map<String, ULong>): Boolean =
    peers.keys != newPeerHeads.keys ||
      peers.any { (peerId, height) ->
        newPeerHeads[peerId] != height
      }

  override fun start() {
    poller.scheduleAtFixedRate(
      /* task = */ timerTask { updatePeerView() },
      /* delay = */ 0.seconds.inWholeMilliseconds,
      /* period = */ config.pollingUpdateInterval.inWholeMilliseconds,
    )
  }

  override fun stop() {
    poller.cancel()
  }
}
