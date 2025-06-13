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
import maru.p2p.MaruPreparedGossipMessage
import maru.p2p.SubscriptionManager
import maru.p2p.ValidationResult
import maru.serialization.Deserializer
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.unsigned.UInt64
import tech.pegasys.teku.networking.p2p.gossip.PreparedGossipMessage
import io.libp2p.core.pubsub.ValidationResult as Libp2pValidationResult

class SequentialTopicHandlerTest {
  private fun makeHandler(
    initialSeq: ULong = 1uL,
    onEvent: (ULong) -> ValidationResult = { ValidationResult.Companion.Valid },
  ): SequentialTopicHandler<ULong> {
    val subscriptionManager = SubscriptionManager<ULong>()
    subscriptionManager.subscribeToBlocks { event -> SafeFuture.completedFuture(onEvent(event)) }
    val deserializer =
      object : Deserializer<ULong> {
        override fun deserialize(bytes: ByteArray): ULong = Bytes.wrap(bytes).toLong().toULong()
      }

    val extractor = SequenceNumberExtractor<ULong> { it }
    return SequentialTopicHandler(
      initialExpectedSequenceNumber = initialSeq,
      subscriptionManager = subscriptionManager,
      deserializer = deserializer,
      sequenceNumberExtractor = extractor,
      topicId = "test-topic",
    )
  }

  private fun makeMessage(seq: ULong): PreparedGossipMessage {
    val bytes = Bytes.ofUnsignedLong(seq.toLong())
    return MaruPreparedGossipMessage(
      origMessage = bytes,
      arrTimestamp = Optional.of(UInt64.valueOf(0)),
      domain = "test-domain",
      topicId = "test-topic",
    )
  }

  @Test
  fun `accepts and processes in-order message`() {
    val handler = makeHandler(initialSeq = 1uL)
    val msg1 = makeMessage(1uL)
    val result1 = handler.handleMessage(msg1).join()
    assertThat(result1).isEqualTo(Libp2pValidationResult.Valid)
    val msg2 = makeMessage(2uL)
    val result2 = handler.handleMessage(msg2).join()
    assertThat(result2).isEqualTo(Libp2pValidationResult.Valid)
  }

  @Test
  fun `queues out-of-order message and processes when expected`() {
    val processed = mutableListOf<ULong>()
    val handler =
      makeHandler(initialSeq = 1uL) {
        processed.add(it)
        ValidationResult.Companion.Valid
      }
    val msg2 = makeMessage(2uL)
    val msg1 = makeMessage(1uL)
    val future2 = handler.handleMessage(msg2)
    assertThat(future2.isDone).isFalse
    val future1 = handler.handleMessage(msg1)
    assertThat(future1.join()).isEqualTo(Libp2pValidationResult.Valid)
    assertThat(future2.join()).isEqualTo(Libp2pValidationResult.Valid)
    assertThat(processed).containsExactly(1uL, 2uL)
  }

  @Test
  fun `ignores duplicate or old messages`() {
    val handler = makeHandler(initialSeq = 2uL)
    val msg1 = makeMessage(1uL)
    val result = handler.handleMessage(msg1).join()
    assertThat(result).isEqualTo(Libp2pValidationResult.Ignore)
  }

  @Test
  fun `ignores out-of-order message if queue is full`() {
    val handler = makeHandler(initialSeq = 1uL)
    // Fill the queue
    for (i in 2uL..1001uL) {
      handler.handleMessage(makeMessage(i))
    }
    // This one should be ignored
    val result = handler.handleMessage(makeMessage(1002uL)).join()
    assertThat(result).isEqualTo(Libp2pValidationResult.Ignore)
  }
}
