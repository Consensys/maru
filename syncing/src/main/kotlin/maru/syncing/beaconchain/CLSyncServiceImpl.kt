/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing.beaconchain

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import maru.consensus.ValidatorProvider
import maru.database.BeaconChain
import maru.metrics.MaruMetricsCategory
import maru.p2p.PeerLookup
import maru.services.LongRunningService
import maru.subscription.InOrderFanoutSubscriptionManager
import maru.subscription.SubscriptionManager
import maru.syncing.CLSyncService
import maru.syncing.beaconchain.pipeline.BeaconChainDownloadPipelineFactory
import maru.syncing.beaconchain.pipeline.BeaconChainPipeline
import net.consensys.linea.metrics.MetricsFacade
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.plugin.services.MetricsSystem

class CLSyncServiceImpl(
  private val beaconChain: BeaconChain,
  private val validatorProvider: ValidatorProvider,
  private val allowEmptyBlocks: Boolean,
  private var executorService: ExecutorService,
  pipelineConfig: BeaconChainDownloadPipelineFactory.Config,
  peerLookup: PeerLookup,
  besuMetrics: MetricsSystem,
  metricsFacade: MetricsFacade,
) : CLSyncService,
  LongRunningService {
  private val log: Logger = LogManager.getLogger(this::class.java)
  private val beaconChainPipeline: AtomicReference<BeaconChainPipeline?> = AtomicReference(null)
  private val syncTarget: AtomicReference<ULong> = AtomicReference(0UL)
  private val syncCompleteHanders: SubscriptionManager<ULong> = InOrderFanoutSubscriptionManager()
  private val blockImporter =
    SyncSealedBlockImporterFactory()
      .create(
        beaconChain = beaconChain,
        validatorProvider = validatorProvider,
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
  private val started = AtomicBoolean(false)
  private val syncLock = Object()

  override fun setSyncTarget(syncTarget: ULong) {
    check(started.get()) { "Sync service must be started before setting sync target" }

    log.info("Syncing started syncTarget={}", syncTarget)
    this.syncTarget.set(syncTarget)

    synchronized(syncLock) {
      // If the pipeline is already running, we don't need to start a new one
      if (beaconChainPipeline.get() == null) {
        startSync()
      }
    }
  }

  private fun startSync() {
    synchronized(syncLock) {
      val startBlock = beaconChain.getLatestBeaconState().latestBeaconBlockHeader.number + 1UL
      val pipeline = pipelineFactory.createPipeline(startBlock)
      this.beaconChainPipeline.set(pipeline)

      pipeline.pipline.start(executorService).handle { _, ex ->
        if (ex != null && ex !is CancellationException) {
          log.error("Sync pipeline failed, restarting", ex)
          pipelineRestartCounter.increment()
          this.beaconChainPipeline.set(null)
          startSync()
        } else {
          val completedSyncTarget = pipeline.target()
          log.info("Sync completed syncTarget={}", completedSyncTarget)
          this.beaconChainPipeline.set(null)
          syncCompleteHanders.notifySubscribers(completedSyncTarget)
        }
      }
    }
  }

  override fun onSyncComplete(handler: (ULong) -> Unit) {
    val subscriptionId = handler.toString()
    syncCompleteHanders.addSyncSubscriber(subscriptionId, handler)
  }

  override fun start() {
    started.set(true)
  }

  override fun stop() {
    started.set(false)
    beaconChainPipeline.get()?.pipline?.abort()
  }
}
