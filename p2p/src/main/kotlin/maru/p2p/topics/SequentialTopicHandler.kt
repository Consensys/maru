/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.topics

import java.util.Optional
import java.util.PriorityQueue
import maru.p2p.LINEA_DOMAIN
import maru.p2p.MaruPreparedGossipMessage
import maru.p2p.SubscriptionManager
import maru.p2p.ValidationResultCode
import maru.serialization.Deserializer
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.unsigned.UInt64
import tech.pegasys.teku.networking.p2p.gossip.PreparedGossipMessage
import tech.pegasys.teku.networking.p2p.gossip.TopicHandler
import io.libp2p.core.pubsub.ValidationResult as Libp2pValidationResult

fun interface SequenceNumberExtractor<T> {
  fun extractSequenceNumber(event: T): ULong
}

class SequentialTopicHandler<T>(
  private var nextExpectedSequenceNumber: ULong,
  private val subscriptionManager: SubscriptionManager<T>,
  private val deserializer: Deserializer<T>,
  private val sequenceNumberExtractor: SequenceNumberExtractor<T>,
  private val topicId: String,
) : TopicHandler {
  companion object {
    fun ValidationResultCode.toLibP2P(): Libp2pValidationResult =
      when (this) {
        ValidationResultCode.ACCEPT -> Libp2pValidationResult.Valid
        ValidationResultCode.REJECT -> Libp2pValidationResult.Invalid
        ValidationResultCode.IGNORE -> Libp2pValidationResult.Ignore
        // TODO: We don't have a case for this yet, so maybe it isn't right
        ValidationResultCode.KEEP_FOR_THE_FUTURE -> Libp2pValidationResult.Ignore
      }
  }

  private val comparator: Comparator<Pair<T, SafeFuture<Libp2pValidationResult>>> =
    Comparator.comparing {
      sequenceNumberExtractor.extractSequenceNumber(it.first)
    }

  private val pendingEvents = PriorityQueue<Pair<T, SafeFuture<Libp2pValidationResult>>>(comparator)

  override fun prepareMessage(
    payload: Bytes,
    arrivalTimestamp: Optional<UInt64>,
  ): PreparedGossipMessage =
    MaruPreparedGossipMessage(
      origMessage = payload,
      arrTimestamp = arrivalTimestamp,
      domain = LINEA_DOMAIN,
      topicId = topicId,
    )

  @Synchronized
  override fun handleMessage(message: PreparedGossipMessage): SafeFuture<Libp2pValidationResult> {
    val deserializedMessage = deserializer.deserialize(message.originalMessage.toArray())
    val sequenceNumber = sequenceNumberExtractor.extractSequenceNumber(deserializedMessage)
    if (sequenceNumber == nextExpectedSequenceNumber) {
      nextExpectedSequenceNumber++
      return subscriptionManager.handleEvent(deserializedMessage).thenApply { it.code.toLibP2P() }.also {
        processPendingBlocks()
      }
    } else {
      val delayedHandlingFuture = SafeFuture<Libp2pValidationResult>()
      pendingEvents.add(deserializedMessage to delayedHandlingFuture)
      return delayedHandlingFuture
    }
  }

  private fun processPendingBlocks() {
    while (pendingEvents.isNotEmpty() &&
      sequenceNumberExtractor.extractSequenceNumber(pendingEvents.peek().first) == nextExpectedSequenceNumber
    ) {
      val (nextEventToHandle, future) = pendingEvents.remove()
      subscriptionManager.handleEvent(nextEventToHandle).thenApply { it.code.toLibP2P() }.propagateTo(future)
    }
  }

  override fun getMaxMessageSize(): Int = 10485760
}
