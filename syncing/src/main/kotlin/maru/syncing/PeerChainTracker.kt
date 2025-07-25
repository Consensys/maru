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
  )

  private var peers = mutableMapOf<String, ULong>()
  private var lastNotifiedTarget: ULong? = null

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

    // Round all heights according to granularity
    val roundedNewPeerHeads = newPeerHeads.mapValues { roundHeight(it.value) }

    // Detect if there are any actual changes
    val hasChanges = hasActualChanges(roundedNewPeerHeads)

    // Update our peers view (removing disconnected peers and updating heights)
    peers = roundedNewPeerHeads.toMutableMap()

    // If there are changes, recalculate the sync target
    if (hasChanges && peers.isNotEmpty()) {
      calculateAndNotifyNewSyncTarget()
    }
  }

  /**
   * Checks if there are actual changes compared to current peer view
   */
  private fun hasActualChanges(newPeerHeads: Map<String, ULong>): Boolean {
    // Check if any peer was disconnected
    if (peers.keys != newPeerHeads.keys) return true

    // Check if any peer's height changed
    return peers.any { (peerId, height) ->
      newPeerHeads[peerId] != height
    }
  }

  /**
   * Calculates new sync target and notifies the handler if it's different from the last notified target
   */
  private fun calculateAndNotifyNewSyncTarget() {
    // Use target selector to determine the best sync target
    val newSyncTarget = targetChainHeadCalculator.selectBestSyncTarget(peers.values.toList())

    // Only notify if the target is different from the last one
    if (newSyncTarget != lastNotifiedTarget) {
      syncTargetUpdateHandler.onChainHeadUpdated(newSyncTarget)
      lastNotifiedTarget = newSyncTarget
    }
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
