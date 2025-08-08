/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import kotlin.random.Random
import kotlin.random.nextULong
import maru.p2p.Message
import maru.p2p.RpcMessageType
import maru.p2p.Version
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StatusMessageSerDeTest {
  private val serDe = StatusMessageSerDe()

  @Test
  fun `can serialize and deserialize same value`() {
    val testValue =
      Message(
        RpcMessageType.STATUS,
        Version.V1,
        Status(Random.nextBytes(32), Random.nextBytes(32), Random.nextULong()),
      )

    val serializedData = serDe.serialize(testValue)
    val deserializedValue = serDe.deserialize(serializedData)

    assertThat(deserializedValue).isEqualTo(testValue)
  }
}
