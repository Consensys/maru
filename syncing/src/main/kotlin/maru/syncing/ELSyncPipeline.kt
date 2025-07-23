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

class ELSyncPipeline : SyncPipeline<ELSyncTarget, ELSyncResult> {
  override fun setSyncTarget(syncTarget: ELSyncTarget): SafeFuture<ELSyncResult> {
    TODO("Not yet implemented")
  }

  override fun onSyncComplete(handler: (ELSyncResult) -> Unit) {
    TODO("Not yet implemented")
  }

  override fun getSyncProgress(): ELSyncResult? {
    TODO("Not yet implemented")
  }

  override fun start() {
    TODO("Not yet implemented")
  }

  override fun stop() {
    TODO("Not yet implemented")
  }
}

data class ELSyncTarget(
  val headRoot: ByteArray,
) : SyncTarget {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ELSyncTarget

    if (!headRoot.contentEquals(other.headRoot)) return false

    return true
  }

  override fun hashCode(): Int = headRoot.contentHashCode()
}

data class ELSyncResult(
  override val syncTarget: ELSyncTarget,
  override val syncStatus: SyncResult.Status,
) : SyncResult<ELSyncTarget>
