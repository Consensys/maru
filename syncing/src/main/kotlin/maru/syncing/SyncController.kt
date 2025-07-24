/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

enum class CLSyncStatus {
  SYNCING,
  SYNCED, // up to head - nearHeadBlocks
}

enum class ELSyncStatus {
  SYNCING,
  SYNCED, // EL has latest SYNCED block from Beacon
}

interface SyncController : SyncTargetUpdateHandler {
  fun getCLSyncStatus(): CLSyncStatus

  fun getElSyncStatus(): ELSyncStatus

  fun onClSyncStatusUpdate(handler: (newStatus: CLSyncStatus) -> Unit)

  fun onElSyncStatusUpdate(handler: (newStatus: ELSyncStatus) -> Unit)

  fun isBeaconChainSynced(): Boolean

  fun isELSynced(): Boolean

  fun isNodeFullInSync(): Boolean = isELSynced() && isBeaconChainSynced()

  fun onBeaconSyncComplete(handler: () -> Unit)

  fun onELSyncComplete(handler: () -> Unit)
}

class SyncControllerImpl() {
  private var clState = CLSyncStatus.SYNCING
  private var elState = ELSyncStatus.SYNCING
}
