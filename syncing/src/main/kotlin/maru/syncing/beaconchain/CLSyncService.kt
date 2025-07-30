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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import maru.core.Validator
import maru.database.BeaconChain
import maru.metrics.MaruMetricsCategory
import maru.p2p.PeerLookup
import maru.services.LongRunningService
import maru.syncing.beaconchain.pipeline.BeaconChainDownloadPipelineFactory
import net.consensys.linea.metrics.MetricsFacade
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

class CLSyncServiceImpl(
  private val beaconChain: BeaconChain,
  private val validators: Set<Validator>,
  private val allowEmptyBlocks: Boolean = true,
  pipelineConfig: BeaconChainDownloadPipelineFactory.Config,
  peerLookup: PeerLookup,
  besuMetrics: MetricsSystem,
  metricsFacade: MetricsFacade,
) : CLSyncService,
  LongRunningService {
  private val log: Logger = LogManager.getLogger(this::class.java)
  private var executorService: ExecutorService? = null
  private val syncTarget: AtomicReference<ULong> = AtomicReference(0UL)
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
    BeaconChainDownloadPipelineFactory(blockImporter, besuMetrics, peerLookup, pipelineConfig) {
      syncTarget.get()
    }
  private val pipelineRestartCounter =
    metricsFacade.createCounter(
      category = MaruMetricsCategory.SYNCHRONIZER,
      name = "beaconchain.restart.counter",
      description = "Count of chain pipeline restarts",
    )

  override fun setSyncTarget(syncTarget: ULong) {
    log.info("Syncing started syncTarget={}", syncTarget)
    this.syncTarget.set(syncTarget)

    // If the pipeline is already running, we don't need to start a new one
    if (pipeline == null) {
      startSync()
    }
  }

  private fun startSync() {
    // no existing pipeline then create a new one using syncTarget and current start block
    if (this.pipeline == null) {
      val startBlock = max(beaconChain.getLatestBeaconState().latestBeaconBlockHeader.number, 1uL)
      val pipeline = pipelineFactory.createPipeline(startBlock)
      this.pipeline = pipeline

      pipeline.start(executorService).handle { _, ex ->
        if (ex != null) {
          log.error("Sync pipeline failed, restarting", ex)
          pipelineRestartCounter.increment()
          this.pipeline = null
          startSync()
        } else {
          log.info("Sync pipeline completed successfully")
          syncCompleteHanders.forEach { handler -> handler(startBlock) }
          this.pipeline = null
        }
      }
    }
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
