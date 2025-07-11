/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BeaconBlocksByRangeRequestSerDeTest {
  private val serDe = BeaconBlocksByRangeRequestSerDe()

  @Test
  fun `can serialize and deserialize request`() {
    val request =
      BeaconBlocksByRangeRequest(
        startBlockNumber = 100UL,
        count = 64UL,
      )

    val serialized = serDe.serialize(request)
    val deserialized = serDe.deserialize(serialized)

    assertThat(deserialized).isEqualTo(request)
    assertThat(deserialized.startBlockNumber).isEqualTo(100UL)
    assertThat(deserialized.count).isEqualTo(64UL)
  }

  @Test
  fun `can serialize and deserialize request with zero values`() {
    val request =
      BeaconBlocksByRangeRequest(
        startBlockNumber = 0UL,
        count = 0UL,
      )

    val serialized = serDe.serialize(request)
    val deserialized = serDe.deserialize(serialized)

    assertThat(deserialized).isEqualTo(request)
    assertThat(deserialized.startBlockNumber).isEqualTo(0UL)
    assertThat(deserialized.count).isEqualTo(0UL)
  }

  @Test
  fun `can serialize and deserialize request with large values`() {
    val request =
      BeaconBlocksByRangeRequest(
        startBlockNumber = ULong.MAX_VALUE,
        count = 1000UL,
      )

    val serialized = serDe.serialize(request)
    val deserialized = serDe.deserialize(serialized)

    assertThat(deserialized).isEqualTo(request)
    assertThat(deserialized.startBlockNumber).isEqualTo(ULong.MAX_VALUE)
    assertThat(deserialized.count).isEqualTo(1000UL)
  }
}
