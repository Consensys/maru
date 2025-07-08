/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.executionlayer.manager

import maru.core.EMPTY_HASH
import maru.core.ExecutionPayload
import maru.extensions.encodeHex
import tech.pegasys.teku.infrastructure.async.SafeFuture

data class ForkChoiceUpdatedResult(
  val payloadStatus: PayloadStatus,
  val payloadId: ByteArray?,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ForkChoiceUpdatedResult

    if (payloadStatus != other.payloadStatus) return false
    if (payloadId != null) {
      if (other.payloadId == null) return false
      if (!payloadId.contentEquals(other.payloadId)) return false
    } else if (other.payloadId != null) {
      return false
    }

    return true
  }

  override fun hashCode(): Int {
    var result = payloadStatus.hashCode()
    result = 31 * result + (payloadId?.contentHashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "ForkChoiceUpdatedResult(payloadStatus=$payloadStatus, payloadId=${payloadId?.encodeHex()})"
}

data class PayloadAttributes(
  val timestamp: Long,
  val prevRandao: ByteArray = EMPTY_HASH,
  val suggestedFeeRecipient: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as PayloadAttributes

    if (timestamp != other.timestamp) return false
    if (!prevRandao.contentEquals(other.prevRandao)) return false
    if (!suggestedFeeRecipient.contentEquals(other.suggestedFeeRecipient)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = timestamp.hashCode()
    result = 31 * result + prevRandao.contentHashCode()
    result = 31 * result + suggestedFeeRecipient.contentHashCode()
    return result
  }

  override fun toString(): String =
    "PayloadAttributes(timestamp=$timestamp, prevRandao=${prevRandao.encodeHex()}, " +
      "suggestedFeeRecipient=${suggestedFeeRecipient.encodeHex()})"
}

interface ExecutionLayerManager {
  fun setHeadAndStartBlockBuilding(
    headHash: ByteArray,
    safeHash: ByteArray,
    finalizedHash: ByteArray,
    nextBlockTimestamp: Long,
    feeRecipient: ByteArray,
  ): SafeFuture<ForkChoiceUpdatedResult>

  fun finishBlockBuilding(): SafeFuture<ExecutionPayload>

  fun setHead(
    headHash: ByteArray,
    safeHash: ByteArray,
    finalizedHash: ByteArray,
  ): SafeFuture<ForkChoiceUpdatedResult>

  fun newPayload(executionPayload: ExecutionPayload): SafeFuture<PayloadStatus>
}
