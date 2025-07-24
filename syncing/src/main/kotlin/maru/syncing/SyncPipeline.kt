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
import maru.services.LongRunningService

interface SyncPipeline<T> {
  fun setSyncTarget(syncTarget: T)

  /**
   * Notifies the handler when the <b>latest<b/> target is reached.
   * If target is updated, onSyncComplete won't be called for previous targets
   */
  fun onSyncComplete(handler: (syncTarget: T) -> Unit)
}

data class ElBlockInfo(
  val blockNumber: ULong,
  val blockHash: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ElBlockInfo

    if (blockNumber != other.blockNumber) return false
    if (!blockHash.contentEquals(other.blockHash)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = blockNumber.hashCode()
    result = 31 * result + blockHash.contentHashCode()
    return result
  }
}

interface ELSyncService

/**
 * Polls the EL for its latest block
 * If it's behind the executionPayload block number in the `beaconChain` by more than `leeway` it sends the
 * status update callback `onStatusChange` and it tries to sync the EL by `executionLayerManager` with the latest known
 * EL hash from the `beaconChain`
 */
class ELSyncServiceImpl(
  private val beaconChain: BeaconChain,
  private val leeway: UInt,
  private val executionLayerManager: ExecutionLayerManager,
  private val onStatusChange: (ELSyncStatus) -> Unit,
) : ELSyncService,
  LongRunningService {
  override fun start() {
    // Implementation to start the EL sync service
  }

  override fun stop() {
    // Implementation to stop the EL sync service
  }
}

interface CLSyncPipeline : SyncPipeline<ULong> {
  fun onSyncChunkPersisted(handler: (ULong) -> Unit)
}

interface FullNodeSyncPipeline {
  fun setClSyncTarget(beaconBlockNumber: ULong)

  fun onElSyncComplete(handler: () -> Unit)

  fun onClSyncComplete(handler: () -> Unit)
}

class CLSyncPipelineImpl :
  CLSyncPipeline,
  LongRunningService {
  override fun onSyncChunkPersisted(handler: (ULong) -> Unit) {
    TODO("Not yet implemented")
  }

  override fun setSyncTarget(syncTarget: ULong) {
    TODO("Not yet implemented")
  }

  override fun onSyncComplete(handler: (ULong) -> Unit) {
    TODO("Not yet implemented")
  }

  override fun start() {
    TODO("Not yet implemented")
  }

  override fun stop() {
    TODO("Not yet implemented")
  }
}
