/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.topics

import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.consensus.qbft.core.types.QbftMessage
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData
import org.junit.jupiter.api.Test

class QbftMessageSerDeTest {
  private val serDe = QbftMessageSerDe()

  @Test
  fun `should serialize and deserialize QbftMessage`() {
    val originalData = Bytes.random(32)
    val originalMessageData = MessageDataSerDe.MaruMessageData(42, originalData)
    val originalQbftMessage =
      object : QbftMessage {
        override fun getData(): MessageData = originalMessageData

        override fun toString(): String = "TestQbftMessage"
      }

    val serialized = serDe.serialize(originalQbftMessage)
    val deserialized = serDe.deserialize(serialized)

    assertThat(deserialized.data).isNotNull
    assertThat(deserialized.data?.data).isEqualTo(originalData)
    assertThat(deserialized.data?.size).isEqualTo(originalData.size())
    assertThat(deserialized.data?.code).isEqualTo(42)
  }

  @Test
  fun `should handle QbftMessage with empty data`() {
    val emptyData = Bytes.EMPTY
    val emptyMessageData = MessageDataSerDe.MaruMessageData(0, emptyData)
    val qbftMessage =
      object : QbftMessage {
        override fun getData(): MessageData = emptyMessageData

        override fun toString(): String = "EmptyQbftMessage"
      }

    val serialized = serDe.serialize(qbftMessage)
    val deserialized = serDe.deserialize(serialized)

    assertThat(deserialized.data).isNotNull
    assertThat(deserialized.data?.data).isEqualTo(emptyData)
    assertThat(deserialized.data?.size).isEqualTo(0)
    assertThat(deserialized.data?.code).isEqualTo(0)
  }
}
