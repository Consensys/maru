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
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import maru.core.SealedBeaconBlock
import maru.p2p.PeerLookup
import org.apache.logging.log4j.LogManager
import tech.pegasys.teku.networking.p2p.reputation.ReputationAdjustment

class DownloadCompleteBlockRangeTask(
  private val peerLookup: PeerLookup,
) {
  private val log: org.apache.logging.log4j.Logger = LogManager.getLogger(this::javaClass)

  fun getCompleteBlockRange(targetRange: SyncTargetRange): CompletableFuture<List<SealedBeaconBlock>> =
    CompletableFuture<List<SealedBeaconBlock>>.supplyAsync {
      var startBlockNumber = targetRange.startBlock
      var count = targetRange.endBlock - targetRange.startBlock + 1uL
      var remaining = count
      val downloadedBlocks = mutableListOf<SealedBeaconBlock>()
      var retries = 0
      do {
        val peer = peerLookup.getPeers().random() // TODO: filter peers? Do we want to use least busy peer?
        try {
          peer
            .sendBeaconBlocksByRange(startBlockNumber, remaining)
            .orTimeout(5L, TimeUnit.SECONDS)
            .thenApply { response ->
              if (response.blocks.isEmpty()) {
                peer.adjustReputation(ReputationAdjustment.SMALL_PENALTY)
                log.debug("No blocks received from peer: {}", peer.id)
                retries++
              } else {
                // TODO: can we check if the blocks are valid?
                // we could check the first block number, and whether the following block numbers increment by 1, as well as the parent roots
                downloadedBlocks.addAll(response.blocks)
                val numBlocks = response.blocks.size.toULong()
                startBlockNumber += numBlocks
                remaining -= numBlocks
              }
            }.join()
        } catch (e: Exception) {
          if (e.cause is TimeoutException) {
            log.debug("Timed out while downloading blocks from peer: {}", peer.id)
            peer.adjustReputation(ReputationAdjustment.LARGE_PENALTY)
          } else {
            log.debug("Failed to download blocks from peer: {}", peer.id, e)
          }
          retries++
        }
        if (retries > 5) {
          log.warn("Failed to download blocks after 5 retries, giving up.")
          throw Exception("Failed to download blocks after 5 retries, giving up.")
          // TODO: If that happens we should probably restart the pipeline
        }
      } while (downloadedBlocks.size.toULong() < count)
      downloadedBlocks
    }
}
