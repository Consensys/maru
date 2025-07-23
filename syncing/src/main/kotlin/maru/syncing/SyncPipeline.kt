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

interface SyncPipeline<T : SyncTarget, R : SyncResult<T>> {
  fun setSyncTarget(syncTarget: T): SafeFuture<R>

  fun onSyncComplete(handler: (syncResult: R) -> Unit)

  fun getSyncProgress(): R?

  fun start(): Unit

  fun stop(): Unit
}

interface SyncResult<T : SyncTarget> {
  val syncTarget: T

  val syncStatus: Status

  enum class Status {
    IN_PROGRESS,
    COMPLETE,
    TARGET_CHANGED,
    FAILED,
  }
}

interface SyncTarget
