/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing
import tech.pegasys.teku.infrastructure.async.SafeFuture

class BeaconSyncPipeline : SyncPipeline<BeaconSyncTarget, BeaconSyncResult> {
  override fun setSyncTarget(syncTarget: BeaconSyncTarget): SafeFuture<BeaconSyncResult> {
    TODO("Not yet implemented")
  }

  override fun onSyncComplete(handler: (BeaconSyncResult) -> Unit) {
    TODO("Not yet implemented")
  }

  override fun getSyncProgress(): BeaconSyncResult? {
    TODO("Not yet implemented")
  }

  override fun start() {
    TODO("Not yet implemented")
  }

  override fun stop() {
    TODO("Not yet implemented")
  }
}

data class BeaconSyncTarget(
  val beaconBlockNumber: ULong,
) : SyncTarget

data class BeaconSyncResult(
  override val syncTarget: BeaconSyncTarget,
  override val syncStatus: SyncResult.Status,
) : SyncResult<BeaconSyncTarget>
