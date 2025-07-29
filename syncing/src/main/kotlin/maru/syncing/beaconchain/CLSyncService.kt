/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing.beaconchain

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import maru.core.Validator
import maru.database.BeaconChain
import maru.p2p.PeerLookup
import maru.services.LongRunningService
import maru.syncing.beaconchain.pipeline.BeaconChainDownloadPipelineFactory
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.plugin.services.MetricsSystem
import org.hyperledger.besu.services.pipeline.Pipeline

/**
 * Service to synchronize the beacon chain with a specified target.
 * It allows setting a sync target and provides a callback mechanism to notify when the sync is complete.
 */
interface CLSyncService {
  /**
   * Sets the target for synchronization.
   * The service will synchronize up to this target block number.
   *
   * @param syncTarget The target block number to synchronize to.
   */
  fun setSyncTarget(syncTarget: ULong)

  /**
   * Notifies the handler when the <b>latest<b/> target is reached.
   * If the target is updated, onSyncComplete won't be called for previous targets
   */
  fun onSyncComplete(handler: (syncTarget: ULong) -> Unit)
}

class CLSyncPipelineImpl(
  private val beaconChain: BeaconChain,
  private val validators: Set<Validator>,
  private val allowEmptyBlocks: Boolean = true,
  downloaderParallelism: UInt = 1u,
  requestSize: UInt = 40u,
  peerLookup: PeerLookup,
  besuMetrics: MetricsSystem,
) : CLSyncService,
  LongRunningService {
  private val log: Logger = LogManager.getLogger(this::class.java)
  private var executorService: ExecutorService? = null
  private val syncCompleteHanders = mutableListOf<(ULong) -> Unit>()
  private var pipeline: Pipeline<*>? = null
  private val blockImporter =
    SyncSealedBlockImporterFactory()
      .create(
        beaconChain = beaconChain,
        validators = validators,
        allowEmptyBlocks = allowEmptyBlocks,
      )
  private var pipelineFactory =
    BeaconChainDownloadPipelineFactory(blockImporter, besuMetrics, peerLookup, downloaderParallelism, requestSize)

  override fun setSyncTarget(syncTarget: ULong) {
    // If the pipeline is already running, abort it before starting a new one
    pipeline?.abort()

    log.info("Syncing started syncTarget={}", syncTarget)
    val startBlock = max(beaconChain.getLatestBeaconState().latestBeaconBlockHeader.number, 1uL)
    val pipeline = pipelineFactory.createPipeline(startBlock, syncTarget)
    val syncCompleteFuture = pipeline.start(executorService)
    syncCompleteFuture.thenApply { syncCompleteHanders.forEach { handler -> handler(startBlock) } }
  }

  override fun onSyncComplete(handler: (ULong) -> Unit) {
    syncCompleteHanders.add(handler)
  }

  override fun start() {
    executorService = Executors.newCachedThreadPool()
  }

  override fun stop() {
    pipeline?.abort()
    executorService?.shutdownNow()
  }
}
