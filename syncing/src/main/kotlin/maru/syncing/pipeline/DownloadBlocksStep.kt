/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing.pipeline

import java.util.concurrent.CompletableFuture
import java.util.function.Function
import maru.core.SealedBeaconBlock
import maru.p2p.PeerLookup

class DownloadBlocksStep(
  private val peerLookup: PeerLookup,
) : Function<SyncTargetRange, CompletableFuture<List<SealedBeaconBlock>>> {
  val downloadCompleteBlockRangeTask = DownloadCompleteBlockRangeTask(peerLookup = peerLookup)

  override fun apply(targetRange: SyncTargetRange): CompletableFuture<List<SealedBeaconBlock>> =
    downloadCompleteBlockRangeTask.getCompleteBlockRange(targetRange)
}
