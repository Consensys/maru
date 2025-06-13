/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.topics

import java.util.NavigableMap
import java.util.Optional
import java.util.PriorityQueue
import maru.core.SealedBeaconBlock
import maru.p2p.LINEA_DOMAIN
import maru.p2p.MaruPreparedGossipMessage
import maru.p2p.SubscriptionManager
import maru.p2p.topics.SealedBlocksTopicHandler.Companion.toLibP2P
import maru.serialization.Serializer
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.unsigned.UInt64
import tech.pegasys.teku.networking.p2p.gossip.PreparedGossipMessage
import tech.pegasys.teku.networking.p2p.gossip.TopicHandler
import io.libp2p.core.pubsub.ValidationResult as Libp2pValidationResult

interface Sequential {
  val sequenceNumber: ULong
}

class SequentialTopicHandler<T : Sequential>(
  private var nextExpectedSequenceNumber: ULong,
  private val subscriptionManager: SubscriptionManager<T>,
  private val serializer: Serializer<T>,
  private val topicId: String,
) : TopicHandler {
  private val pendingBlocks = PriorityQueue<Sequential>()
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

  override fun handleMessage(message: PreparedGossipMessage): SafeFuture<Libp2pValidationResult> {
    val deserializaedMessage = sealedBeaconBlockSerializer.deserialize(message.originalMessage.toArray())
    return subscriptionManager.handleEvent(deserializaedMessage).thenApply { it.code.toLibP2P() }

  }

  override fun getMaxMessageSize(): Int = 10485760
}
