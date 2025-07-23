/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

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

interface ELSyncPipeline : SyncPipeline<ElBlockInfo>

interface CLSyncPipeline : SyncPipeline<ULong> {
  fun onSyncChunkPersisted(handler: (ULong) -> Unit)
}

interface FullNodeSyncPipeline {
  fun setClSyncTarget(beaconBlockNumber: ULong)

  fun onElSyncComplete(handler: () -> Unit)

  fun onClSyncComplete(handler: () -> Unit)
}

class ELSyncPipelineImpl : ELSyncPipeline {
  override fun setSyncTarget(syncTarget: ElBlockInfo) {
    TODO("Not yet implemented")
  }

  override fun onSyncComplete(handler: (ElBlockInfo) -> Unit) {
    TODO("Not yet implemented")
  }
}

class CLSyncPipelineImpl : CLSyncPipeline {
  override fun onSyncChunkPersisted(handler: (ULong) -> Unit) {
    TODO("Not yet implemented")
  }

  override fun setSyncTarget(syncTarget: ULong) {
    TODO("Not yet implemented")
  }

  override fun onSyncComplete(handler: (ULong) -> Unit) {
    TODO("Not yet implemented")
  }
}

/**
 * Responsible to orchestrate
 */
class FullNodeSyncPipelineImpl(
  clSyncPipelineImpl: CLSyncPipelineImpl,
  elSyncPipelineImpl: ELSyncPipelineImpl,
) : FullNodeSyncPipeline {
  override fun setClSyncTarget(beaconBlockNumber: ULong) {
    TODO("Not yet implemented")
  }

  override fun onElSyncComplete(handler: () -> Unit) {
    TODO("Not yet implemented")
  }

  override fun onClSyncComplete(handler: () -> Unit) {
    TODO("Not yet implemented")
  }
}
