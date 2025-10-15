/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.topics

import maru.p2p.ValidationResult.Companion.Valid.code
import maru.p2p.topics.MessageDataSerDe.MaruMessageData
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MessageDataSerDeTest {
  private val serDe = MessageDataSerDe()

  @Test
  fun `should serialize and deserialize MessageData correctly`() {
    val originalData = Bytes.random(32)
    val originalMessage = MaruMessageData(code = 42, data = originalData)
    val serialized = serDe.serialize(originalMessage)
    val deserialized = serDe.deserialize(serialized)

    assertThat(deserialized.data).isEqualTo(originalData)
    assertThat(deserialized.size).isEqualTo(originalData.size())
    assertThat(deserialized.code).isEqualTo(42)
  }

  @Test
  fun `should handle empty message data`() {
    val originalData = Bytes.EMPTY
    val originalMessage = MaruMessageData(code = 0, data = originalData)
    val serialized = serDe.serialize(originalMessage)
    val deserialized = serDe.deserialize(serialized)

    assertThat(deserialized.data).isEqualTo(originalData)
    assertThat(deserialized.size).isEqualTo(0)
  }
}
