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
import java.util.concurrent.atomic.AtomicLong
import maru.p2p.LINEA_DOMAIN
import maru.p2p.MaruPreparedGossipMessage
import maru.p2p.SubscriptionManager
import maru.p2p.ValidationResultCode
import maru.serialization.Deserializer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.unsigned.UInt64
import tech.pegasys.teku.networking.p2p.gossip.PreparedGossipMessage
import tech.pegasys.teku.networking.p2p.gossip.TopicHandler
import io.libp2p.core.pubsub.ValidationResult as Libp2pValidationResult

fun interface SequenceNumberExtractor<T> {
  fun extractSequenceNumber(event: T): ULong
}

/**
 * Topic handler which triggers event handling only in case there's a "next" event, defined by its sequence number
 *
 * When there's a P2P message that is from the future, it will ba passed to subscriptionManager later, when all the
 * previous events are handled
 * Messages behind the current expected sequence number are not validated and ignored.
 *
 * Note that messages ahead of the current expected sequence number won't be propagated over the network until they're
 * handled
 * @param sequenceNumberExtractor definition of sequentiality for T
 */
class SequentialTopicHandler<T>(
  initialExpectedSequenceNumber: ULong,
  private val subscriptionManager: SubscriptionManager<T>,
  private val deserializer: Deserializer<T>,
  private val sequenceNumberExtractor: SequenceNumberExtractor<T>,
  private val topicId: String,
  private val maxQueueSize: Int = 1000,
) : TopicHandler {
  private val log: Logger = LogManager.getLogger(this::javaClass)

  companion object {
    fun ValidationResultCode.toLibP2P(): Libp2pValidationResult =
      when (this) {
        ValidationResultCode.ACCEPT -> Libp2pValidationResult.Valid
        ValidationResultCode.REJECT -> Libp2pValidationResult.Invalid
        ValidationResultCode.IGNORE -> Libp2pValidationResult.Ignore
      }
  }

  private val nextExpectedSequenceNumber = AtomicLong(initialExpectedSequenceNumber.toLong())

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
  override fun handleMessage(message: PreparedGossipMessage): SafeFuture<Libp2pValidationResult> =
    try {
      val deserializedMessage = deserializer.deserialize(message.originalMessage.toArray())
      val sequenceNumber = sequenceNumberExtractor.extractSequenceNumber(deserializedMessage)
      val nextExpectedSequenceNumber = nextExpectedSequenceNumber.get().toULong()
      when {
        sequenceNumber == nextExpectedSequenceNumber -> {
          log.debug("Handling message with sequenceNumber={}", sequenceNumber)
          val futureToReturn = handleEvent(deserializedMessage)
          futureToReturn.thenAccept {
            processNextPendingEvent()
          }
          futureToReturn
        }

        sequenceNumber > nextExpectedSequenceNumber && pendingEvents.size < maxQueueSize -> {
          log.debug(
            "Adding message with sequenceNumber={} to the queue, expectedSequenceNumber={}",
            sequenceNumber,
            nextExpectedSequenceNumber,
          )
          val delayedHandlingFuture = SafeFuture<Libp2pValidationResult>()
          pendingEvents.add(deserializedMessage to delayedHandlingFuture)
          // Note that it will be completed only when it's handled
          delayedHandlingFuture
        }

        else -> {
          log.debug(
            "Ignoring message with sequenceNumber={}, expectedSequenceNumber={}",
            sequenceNumber,
            nextExpectedSequenceNumber,
          )
          SafeFuture.completedFuture(Libp2pValidationResult.Ignore)
        }
      }
    } catch (th: Throwable) {
      log.error("Unexpected exception while handling message=$message with id=${message.messageId}", th)
      SafeFuture.completedFuture(Libp2pValidationResult.Invalid)
    }

  private fun processNextPendingEvent() {
    if (pendingEvents.isNotEmpty() &&
      sequenceNumberExtractor.extractSequenceNumber(pendingEvents.peek().first) ==
      nextExpectedSequenceNumber
        .get()
        .toULong()
    ) {
      val (nextEventToHandle, future) = pendingEvents.remove()
      handleEvent(nextEventToHandle)
        .whenSuccess { processNextPendingEvent() }
        .propagateTo(future)
    }
  }

  private fun handleEvent(event: T): SafeFuture<Libp2pValidationResult> =
    subscriptionManager.handleEvent(event).thenApply {
      nextExpectedSequenceNumber.incrementAndGet()
      it.code.toLibP2P()
    }

  override fun getMaxMessageSize(): Int = 10485760
}
