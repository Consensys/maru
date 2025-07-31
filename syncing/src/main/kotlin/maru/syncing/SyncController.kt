/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import maru.consensus.ValidatorProvider
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import maru.p2p.PeerLookup
import maru.p2p.PeersHeadBlockProvider
import maru.services.LongRunningService
import maru.syncing.beaconchain.CLSyncServiceImpl
import maru.syncing.beaconchain.pipeline.BeaconChainDownloadPipelineFactory
import net.consensys.linea.metrics.MetricsFacade
import org.hyperledger.besu.plugin.services.MetricsSystem

class SyncControllerImpl(
  private var clState: CLSyncStatus = CLSyncStatus.SYNCED, // Change both to SYNCING by default
  private var elState: ELSyncStatus = ELSyncStatus.SYNCED,
) : SyncStatusProvider,
  SyncTargetUpdateHandler {
  var elSyncHandler: (ELSyncStatus) -> Unit = {}
  var clSyncHandler: (CLSyncStatus) -> Unit = {}

  fun elSyncStatusWasUpdated(newStatus: ELSyncStatus) {
    elState = newStatus
    elSyncHandler(elState)
  }

  override fun getCLSyncStatus(): CLSyncStatus = clState

  override fun getElSyncStatus(): ELSyncStatus = elState

  override fun onClSyncStatusUpdate(handler: (CLSyncStatus) -> Unit) {
    clSyncHandler = handler
  }

  override fun onElSyncStatusUpdate(handler: (ELSyncStatus) -> Unit) {
    elSyncHandler = handler
  }

  override fun isBeaconChainSynced(): Boolean = clState == CLSyncStatus.SYNCED

  override fun isELSynced(): Boolean = elState == ELSyncStatus.SYNCED

  override fun onBeaconSyncComplete(handler: () -> Unit) {
    TODO("Not yet implemented")
  }

  override fun onELSyncComplete(handler: () -> Unit) {
    TODO("Not yet implemented")
  }

  override fun onFullSyncComplete(handler: () -> Unit) {
    TODO("Not yet implemented")
  }

  override fun onChainHeadUpdated(beaconBlockNumber: ULong) {
    TODO("Not yet implemented")
  }

  companion object {
    fun create(
      beaconChain: BeaconChain,
      elManager: ExecutionLayerManager,
      peersHeadsProvider: PeersHeadBlockProvider,
      validatorProvider: ValidatorProvider,
      peerLookup: PeerLookup,
      besuMetrics: MetricsSystem,
      metricsFacade: MetricsFacade,
      targetChainHeadCalculator: SyncTargetSelector = MostFrequentHeadTargetSelector(),
      peerChainTrackerConfig: PeerChainTracker.Config,
      allowEmptyBlocks: Boolean = true,
    ): SyncStatusProvider {
      val controller = SyncControllerImpl()

      val elSyncService =
        ELSyncServiceImpl(
          beaconChain = beaconChain,
          leeway = 10u,
          executionLayerManager = elManager,
          onStatusChange = controller::elSyncStatusWasUpdated,
        )
      val clSyncPipeline =
        CLSyncServiceImpl(
          beaconChain = beaconChain,
          validatorProvider = validatorProvider,
          allowEmptyBlocks = allowEmptyBlocks,
          BeaconChainDownloadPipelineFactory.Config(),
          peerLookup = peerLookup,
          besuMetrics = besuMetrics,
          metricsFacade = metricsFacade,
        )

      val peerChainTracker =
        PeerChainTracker(
          peersHeadsProvider = peersHeadsProvider,
          syncTargetUpdateHandler = controller,
          targetChainHeadCalculator = targetChainHeadCalculator,
          config = peerChainTrackerConfig,
        )

      return SyncControllerManager(
        syncStatusController = controller,
        elSyncServicer = elSyncService,
        clSyncPipeline = clSyncPipeline,
        peerChainTracker = peerChainTracker,
      )
    }
  }
}

internal class SyncControllerManager(
  val syncStatusController: SyncStatusProvider,
  val elSyncServicer: LongRunningService,
  val clSyncPipeline: LongRunningService,
  val peerChainTracker: PeerChainTracker,
) : SyncStatusProvider by syncStatusController,
  LongRunningService {
  override fun start() {
    clSyncPipeline.start()
    elSyncServicer.start()
    peerChainTracker.start()
  }

  override fun stop() {
    clSyncPipeline.stop()
    elSyncServicer.stop()
    peerChainTracker.stop()
  }
}
