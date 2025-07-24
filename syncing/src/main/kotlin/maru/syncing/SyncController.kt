/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import maru.p2p.PeersHeadBlockProvider
import maru.services.LongRunningService

enum class CLSyncStatus {
  SYNCING,
  SYNCED, // up to head - nearHeadBlocks
}

enum class ELSyncStatus {
  SYNCING,
  SYNCED, // EL has latest SYNCED block from Beacon
}

interface SyncStatusProvider {
  fun getCLSyncStatus(): CLSyncStatus

  fun getElSyncStatus(): ELSyncStatus

  fun onClSyncStatusUpdate(handler: (newStatus: CLSyncStatus) -> Unit)

  fun onElSyncStatusUpdate(handler: (newStatus: ELSyncStatus) -> Unit)

  fun isBeaconChainSynced(): Boolean

  fun isELSynced(): Boolean

  fun isNodeFullInSync(): Boolean = isELSynced() && isBeaconChainSynced()

  fun onBeaconSyncComplete(handler: () -> Unit)

  fun onELSyncComplete(handler: () -> Unit)

  fun onFullSyncComplete(handler: () -> Unit)
}

class SyncControllerImpl(
  private var clState: CLSyncStatus = CLSyncStatus.SYNCED, // Change both to SYNCING by default
  private var elState: ELSyncStatus = ELSyncStatus.SYNCED,
) : SyncStatusProvider,
  SyncTargetUpdateHandler {
  fun elSyncStatusWasUpdated(newStatus: ELSyncStatus) {
    elState = newStatus
    // Call onELSyncComplete subscribers
  }

  override fun getCLSyncStatus(): CLSyncStatus = clState

  override fun getElSyncStatus(): ELSyncStatus = elState

  override fun onClSyncStatusUpdate(handler: (CLSyncStatus) -> Unit) {
    TODO("Not yet implemented")
  }

  override fun onElSyncStatusUpdate(handler: (ELSyncStatus) -> Unit) {
    // TODO: Implement
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
      targetChainHeadCalculator: SyncTargetSelector = MostFrequentHeadTargetSelector(),
    ): SyncStatusProvider {
      val controller = SyncControllerImpl()

      val elSyncService =
        ELSyncServiceImpl(
          beaconChain = beaconChain,
          leeway = 10u,
          executionLayerManager = elManager,
          onStatusChange = controller::elSyncStatusWasUpdated,
        )
      val clSyncPipeline = CLSyncPipelineImpl()

      val peerChainTracker =
        PeerChainTracker(
          peersHeadsProvider = peersHeadsProvider,
          syncTargetUpdateHandler = controller,
          targetChainHeadCalculator = targetChainHeadCalculator,
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
