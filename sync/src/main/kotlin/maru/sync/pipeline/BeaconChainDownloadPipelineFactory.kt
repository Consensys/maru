/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.sync.pipeline

import maru.consensus.blockimport.SealedBeaconBlockImporter
import maru.p2p.PeerLookup
import maru.p2p.ValidationResult
import org.hyperledger.besu.metrics.BesuMetricCategory
import org.hyperledger.besu.plugin.services.MetricsSystem
import org.hyperledger.besu.services.pipeline.Pipeline
import org.hyperledger.besu.services.pipeline.PipelineBuilder

class BeaconChainDownloadPipelineFactory(
  private val blockImporter: SealedBeaconBlockImporter<ValidationResult>,
  private val metricsSystem: MetricsSystem,
  private val peerLookup: PeerLookup,
  private val downloaderParallelism: Int,
  private val requestSize: Int,
) {
  init {
    require(requestSize > 0) { "Request size must be greater than 0" }
  }

  fun createPipeline(
    startBlock: ULong,
    endBlock: ULong,
  ): Pipeline<SyncTargetRange?> {
    check(startBlock <= endBlock) { "Start block ($startBlock) must be less than or equal to end block ($endBlock)" }

    val syncTargetRangeSequence = createTargetRangeSequence(startBlock, endBlock)
    val downloadBlocksStep = DownloadBlocksStep(peerLookup)
    val importBlocksStep = ImportBlocksStep(blockImporter)

    return PipelineBuilder
      .createPipelineFrom(
        "blockNumbers",
        syncTargetRangeSequence.iterator(),
        1,
        metricsSystem.createLabelledCounter(
          BesuMetricCategory.SYNCHRONIZER,
          "chain_download_pipeline_processed_total",
          "Number of entries process by each chain download pipeline stage",
          "step",
          "action",
        ),
        true,
        "importBlocks",
      ).thenProcessAsyncOrdered("downloadBlocks", downloadBlocksStep, downloaderParallelism)
      .andFinishWith("importBlocks", importBlocksStep)
  }

  private fun createTargetRangeSequence(
    startBlock: ULong,
    endBlock: ULong,
  ): Sequence<SyncTargetRange> =
    sequence {
      var currentStart = startBlock
      while (currentStart <= endBlock) {
        // Calculate range size safely to avoid overflow
        val remainingBlocks = endBlock - currentStart + 1uL
        val rangeSize = minOf(remainingBlocks, requestSize.toULong())

        // Calculate currentEnd safely without underflow or overflow
        val currentEnd = currentStart + rangeSize - 1uL

        yield(SyncTargetRange(currentStart, currentEnd))

        // Prevent overflow when currentEnd is ULong.MAX_VALUE
        if (currentEnd == ULong.MAX_VALUE || currentEnd >= endBlock) {
          break
        }
        currentStart = currentEnd + 1uL
      }
    }
}
