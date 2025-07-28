/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import java.util.concurrent.atomic.AtomicReference
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
  private val stateLock = Any()

  @Volatile
  private var syncState: SyncState = SyncState(clState, elState)

  // Handlers are set once during initialization, so no need for @Volatile or synchronization
  private var elSyncHandler: (ELSyncStatus) -> Unit = {}
  private var clSyncHandler: (CLSyncStatus) -> Unit = {}
  private var beaconSyncCompleteHandler: () -> Unit = {}
  private var fullSyncCompleteHandler: () -> Unit = {}

  private val currentSyncTarget = AtomicReference<ULong?>(null)

  init {
    // Set up CL sync completion handler
    clSyncService.onSyncComplete { syncTarget ->
      updateClSyncStatus(CLSyncStatus.SYNCED)
    }
  }

  fun updateElSyncStatus(newStatus: ELSyncStatus) {
    var shouldNotifyFullSync = false

    synchronized(stateLock) {
      if (syncState.clStatus == CLSyncStatus.SYNCING) return
      val previousState = syncState
      syncState = syncState.copy(elStatus = newStatus)

      // Check if full sync is complete after EL status change
      if (previousState.elStatus == ELSyncStatus.SYNCING && syncState.elStatus == ELSyncStatus.SYNCED) {
        shouldNotifyFullSync = isNodeFullInSync()
      }
    }

    // Notify handlers outside of synchronized block - handlers are immutable after init
    elSyncHandler(newStatus) // Still notify with the original status for transparency
    if (shouldNotifyFullSync) {
      fullSyncCompleteHandler()
    }
  }

  fun updateClSyncStatus(newStatus: CLSyncStatus) {
    var shouldNotifyBeaconSync = false
    var shouldNotifyFullSync = false
    var shouldNotifyElStatusChange = false
    var elStatusToNotify: ELSyncStatus = ELSyncStatus.SYNCING
    val currentState: SyncState

    synchronized(stateLock) {
      val previousState = syncState

      // When CL starts syncing, EL should also be marked as syncing
      val newElStatus =
        if (newStatus == CLSyncStatus.SYNCING && syncState.elStatus == ELSyncStatus.SYNCED) {
          shouldNotifyElStatusChange = true
          elStatusToNotify = ELSyncStatus.SYNCING
          ELSyncStatus.SYNCING
        } else {
          syncState.elStatus
        }

      syncState = SyncState(newStatus, newElStatus)
      currentState = syncState

      if (previousState.clStatus == CLSyncStatus.SYNCING && currentState.clStatus == CLSyncStatus.SYNCED) {
        shouldNotifyBeaconSync = true
        shouldNotifyFullSync = isNodeFullInSync()
      }
    }

    // Notify handlers outside of synchronized block
    clSyncHandler(newStatus)
    if (shouldNotifyElStatusChange) {
      // Notify EL handler directly instead of calling updateElSyncStatus to avoid race condition
      elSyncHandler(elStatusToNotify)
    }
    if (shouldNotifyBeaconSync) {
      beaconSyncCompleteHandler()
    }
    if (shouldNotifyFullSync) {
      fullSyncCompleteHandler()
    }
  }

  override fun getCLSyncStatus(): CLSyncStatus = syncState.clStatus

  override fun getElSyncStatus(): ELSyncStatus = syncState.elStatus

  override fun onClSyncStatusUpdate(handler: (CLSyncStatus) -> Unit) {
    clSyncHandler = handler
  }

  override fun onElSyncStatusUpdate(handler: (ELSyncStatus) -> Unit) {
    elSyncHandler = handler
  }

  override fun isBeaconChainSynced(): Boolean = syncState.clStatus == CLSyncStatus.SYNCED

  override fun isELSynced(): Boolean = syncState.elStatus == ELSyncStatus.SYNCED

  override fun onBeaconSyncComplete(handler: () -> Unit) {
    beaconSyncCompleteHandler = handler
  }

  override fun onFullSyncComplete(handler: () -> Unit) {
    fullSyncCompleteHandler = handler
  }

  override fun onChainHeadUpdated(beaconBlockNumber: ULong) {
    val previousTarget = currentSyncTarget.getAndSet(beaconBlockNumber)

    if (previousTarget == beaconBlockNumber) {
      return
    }

    // Only trigger sync if conditions are met
    if (isBeaconChainOutOfSync(beaconBlockNumber)) {
      // Update CL sync status to SYNCING and propagate sync target
      updateClSyncStatus(CLSyncStatus.SYNCING)
      clSyncService.setSyncTarget(beaconBlockNumber)
    } else if (syncState.clStatus == CLSyncStatus.SYNCING) {
      // Handle case where controller starts in SYNCING but target matches current head
      updateClSyncStatus(CLSyncStatus.SYNCED)
      clSyncService.setSyncTarget(beaconBlockNumber)
    }
  }

  private fun isBeaconChainOutOfSync(syncTarget: ULong): Boolean {
    val currentHead = beaconChain.getLatestBeaconState().latestBeaconBlockHeader.number
    return syncTarget > currentHead
  }

  // Method for testing - captures all state atomically from a single syncState read
  internal fun captureStateSnapshot(): SyncState = syncState

  companion object {
    fun create(
      beaconChain: BeaconChain,
      elManager: ExecutionLayerManager,
      peersHeadsProvider: PeersHeadBlockProvider,
      targetChainHeadCalculator: SyncTargetSelector = MostFrequentHeadTargetSelector(),
    ): SyncStatusProvider {
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
