/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import java.util.function.Supplier

data class SyncStatus(
  val isSyncing: Boolean,
  val headBeaconBlockNumber: ULong,
  val syncDistance: ULong, // (headBeaconBlockNumber - latestBeaconBlockNumber_on_node)
)

interface SyncStatusProvider : Supplier<SyncStatus>
