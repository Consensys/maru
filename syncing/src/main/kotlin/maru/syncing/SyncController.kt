/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import maru.p2p.PeersHeadBlockProvider
import maru.services.LongRunningService

internal data class SyncState(
  val clStatus: CLSyncStatus,
  val elStatus: ELSyncStatus,
)

class SyncControllerImpl(
  private val beaconChain: BeaconChain,
  private val clSyncService: CLSyncService,
  clState: CLSyncStatus = CLSyncStatus.SYNCING,
  elState: ELSyncStatus = ELSyncStatus.SYNCING,
) : SyncStatusProvider,
  SyncTargetUpdateHandler {
  private val lock = ReentrantReadWriteLock()
  private var currentState = SyncState(clState, elState)
  private var currentSyncTarget: ULong? = null

  private var clSyncHandler: (CLSyncStatus) -> Unit = {}
  private var elSyncHandler: (ELSyncStatus) -> Unit = {}
  private var beaconSyncCompleteHandler: () -> Unit = {}
  private var fullSyncCompleteHandler: () -> Unit = {}

  init {
    clSyncService.onSyncComplete { syncTarget ->
      updateClSyncStatus(CLSyncStatus.SYNCED)
    }
  }

  override fun getCLSyncStatus(): CLSyncStatus = lock.read { currentState.clStatus }

  override fun getElSyncStatus(): ELSyncStatus = lock.read { currentState.elStatus }

  override fun isNodeFullInSync(): Boolean = lock.read { isELSynced() && isBeaconChainSynced() }

  override fun isBeaconChainSynced(): Boolean = lock.read { currentState.clStatus == CLSyncStatus.SYNCED }

  override fun isELSynced(): Boolean = lock.read { currentState.elStatus == ELSyncStatus.SYNCED }

  override fun onClSyncStatusUpdate(handler: (CLSyncStatus) -> Unit) {
    clSyncHandler = handler
  }

  override fun onElSyncStatusUpdate(handler: (ELSyncStatus) -> Unit) {
    elSyncHandler = handler
  }

  override fun onBeaconSyncComplete(handler: () -> Unit) {
    beaconSyncCompleteHandler = handler
  }

  override fun onFullSyncComplete(handler: () -> Unit) {
    fullSyncCompleteHandler = handler
  }

  fun updateClSyncStatus(newStatus: CLSyncStatus) {
    val events =
      lock.write {
        val previousState = currentState

        if (previousState.clStatus == newStatus) return@write emptyList()

        // When CL starts syncing, EL must also be syncing (EL follows CL rule)
        val newElStatus =
          when {
            newStatus == CLSyncStatus.SYNCING -> ELSyncStatus.SYNCING
            else -> previousState.elStatus
          }

        currentState = SyncState(newStatus, newElStatus)

        buildList {
          add { clSyncHandler(newStatus) }

          // If EL status changed due to CL change, notify EL handlers
          if (newElStatus != previousState.elStatus) {
            add { elSyncHandler(newElStatus) }
          }

          // If CL moved from SYNCING to SYNCED, beacon sync is complete
          if (previousState.clStatus == CLSyncStatus.SYNCING && newStatus == CLSyncStatus.SYNCED) {
            add { beaconSyncCompleteHandler() }

            // Check if this makes the node fully synced
            if (isNodeFullInSync()) {
              add { fullSyncCompleteHandler() }
            }
          }
        }
      }

    // Fire events outside of lock
    events.forEach { it() }
  }

  fun updateElSyncStatus(newStatus: ELSyncStatus) {
    val events =
      lock.write {
        val previousState = currentState

        // EL can't be synced if CL is still syncing (EL follows CL rule)
        if (previousState.clStatus == CLSyncStatus.SYNCING ||
          previousState.elStatus == newStatus
        ) {
          return@write emptyList()
        }

        currentState = SyncState(previousState.clStatus, newStatus)

        // Collect events to fire
        buildList {
          add { elSyncHandler(newStatus) }

          // Check if this transition from EL SYNCING->SYNCED when CL is already SYNCED makes us fully synced
          if (previousState.elStatus == ELSyncStatus.SYNCING &&
            newStatus == ELSyncStatus.SYNCED &&
            currentState.clStatus == CLSyncStatus.SYNCED
          ) {
            add { fullSyncCompleteHandler() }
          }
        }
      }

    // Fire events outside of lock
    events.forEach { it() }
  }

  override fun onChainHeadUpdated(syncTarget: ULong) {
    val previousTarget =
      lock.write {
        val prev = currentSyncTarget
        currentSyncTarget = syncTarget
        prev
      }

    // Early return if same target (prevents redundant operations)
    if (previousTarget == syncTarget) {
      return
    }

    val currentHead = beaconChain.getLatestBeaconState().latestBeaconBlockHeader.number

    if (syncTarget > currentHead) {
      updateClSyncStatus(CLSyncStatus.SYNCING)
      clSyncService.setSyncTarget(syncTarget)
    } else {
      // We're caught up or ahead, but check if we were previously syncing
      val currentClStatus = lock.read { currentState.clStatus }
      if (currentClStatus == CLSyncStatus.SYNCING) {
        // Transition from SYNCING to SYNCED
        updateClSyncStatus(CLSyncStatus.SYNCED)
        clSyncService.setSyncTarget(syncTarget)
      }
      // If already SYNCED, do nothing
    }
  }

  // Helper method for testing
  internal fun captureStateSnapshot(): SyncState = lock.read { currentState }

  companion object {
    fun create(
      beaconChain: BeaconChain,
      elManager: ExecutionLayerManager,
      peersHeadsProvider: PeersHeadBlockProvider,
      targetChainHeadCalculator: SyncTargetSelector = MostFrequentHeadTargetSelector(),
      peerChainTrackerConfig: PeerChainTracker.Config,
    ): SyncControllerManager {
      val clSyncPipeline = CLSyncPipelineImpl()

      val controller =
        SyncControllerImpl(
          beaconChain = beaconChain,
          clSyncService = clSyncPipeline,
        )

      val elSyncService =
        ELSyncServiceImpl(
          beaconChain = beaconChain,
          leeway = 10u,
          executionLayerManager = elManager,
          onStatusChange = controller::updateElSyncStatus,
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
        elSyncService = elSyncService,
        clSyncPipeline = clSyncPipeline,
        peerChainTracker = peerChainTracker,
      )
    }
  }
}

class SyncControllerManager(
  val syncStatusController: SyncControllerImpl,
  val elSyncService: LongRunningService,
  val clSyncPipeline: LongRunningService,
  val peerChainTracker: PeerChainTracker,
) : SyncStatusProvider by syncStatusController,
  LongRunningService {
  override fun start() {
    // TODO: remove when clSyncService is implemented
    syncStatusController.updateClSyncStatus(CLSyncStatus.SYNCED)
    // TODO: remove when elSyncService is implemented
    syncStatusController.updateElSyncStatus(ELSyncStatus.SYNCED)
    clSyncPipeline.start()
    elSyncService.start()
    peerChainTracker.start()
  }

  override fun stop() {
    clSyncPipeline.stop()
    elSyncService.stop()
    peerChainTracker.stop()
    // Setting to default status so that SYNCING -> SYNCED will actually trigger the callbacks
    syncStatusController.updateClSyncStatus(CLSyncStatus.SYNCING)
    syncStatusController.updateElSyncStatus(ELSyncStatus.SYNCING)
  }
}
