/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.blockimport

import maru.config.consensus.ElFork
import maru.config.consensus.qbft.DifficultyAwareQbftConfig
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import maru.consensus.NewBlockHandler
import maru.core.BeaconBlock
import maru.executionlayer.manager.ExecutionLayerManager
import org.apache.logging.log4j.LogManager
import tech.pegasys.teku.infrastructure.async.SafeFuture

/**
 * A block importer that is aware of EL forks and delegates to the appropriate
 * ExecutionLayerManager based on the fork spec of the block.
 */
class ElForkAwareBlockImporter(
  private val forksSchedule: ForksSchedule,
  private val elManagerMap: Map<ElFork, ExecutionLayerManager>,
  private val importerName: String,
) : NewBlockHandler<Unit> {
  companion object {
    /**
     * Extracts the EL fork from a ForkSpec based on its consensus configuration.
     */
    fun ForkSpec.extractElFork(): ElFork =
      when (configuration) {
        is QbftConsensusConfig -> (configuration as QbftConsensusConfig).elFork
        is DifficultyAwareQbftConfig -> (configuration as DifficultyAwareQbftConfig).postTtdConfig.elFork
        else -> throw IllegalStateException(
          "Current fork isn't QBFT nor DifficultyAwareQbft, this case is not supported yet! forkSpec=$this",
        )
      }
  }

  private val log = LogManager.getLogger(this.javaClass)

  override fun handleNewBlock(beaconBlock: BeaconBlock): SafeFuture<Unit> {
    val forkSpec = forksSchedule.getForkByTimestamp(beaconBlock.beaconBlockHeader.timestamp)
    val elFork = forkSpec.extractElFork()

    val executionLayerManager =
      elManagerMap[elFork]
        ?: throw IllegalStateException("No execution layer manager found for EL fork: $elFork")

    val executionPayload = beaconBlock.beaconBlockBody.executionPayload

    return executionLayerManager
      .newPayload(executionPayload)
      .handleException { e ->
        log.error(
          "Error importing execution payload to {} for elBlockNumber={} elFork={}",
          importerName,
          executionPayload.blockNumber,
          elFork,
          e,
        )
      }.thenApply {
        log.debug(
          "Imported elBlockNumber={} to {} with elFork={}",
          executionPayload.blockNumber,
          importerName,
          elFork,
        )
      }
  }
}
