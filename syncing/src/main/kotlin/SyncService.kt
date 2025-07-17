/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
import maru.core.Protocol
import maru.syncing.SyncStatus
import maru.syncing.SyncStatusProvider

class SyncService :
  Protocol,
  SyncStatusProvider {
  override fun start() {
    TODO("Not yet implemented")
  }

  override fun stop() {
    TODO("Not yet implemented")
  }

  override fun get(): SyncStatus {
    TODO("Not yet implemented")
  }
}
