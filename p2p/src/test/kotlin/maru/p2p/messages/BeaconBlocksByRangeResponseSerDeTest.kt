/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import maru.core.ext.DataGenerators
import maru.serialization.rlp.RLPSerializers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BeaconBlocksByRangeResponseSerDeTest {
  private val serDe = BeaconBlocksByRangeResponseSerDe(RLPSerializers.SealedBeaconBlockSerializer)

  @Test
  fun `can serialize and deserialize empty response`() {
    val response = BeaconBlocksByRangeResponse(blocks = emptyList())

    val serialized = serDe.serialize(response)
    val deserialized = serDe.deserialize(serialized)

    assertThat(deserialized).isEqualTo(response)
    assertThat(deserialized.blocks).isEmpty()
  }

  @Test
  fun `can serialize and deserialize response with single block`() {
    val block = DataGenerators.randomSealedBeaconBlock(number = 100UL)
    val response = BeaconBlocksByRangeResponse(blocks = listOf(block))

    val serialized = serDe.serialize(response)
    val deserialized = serDe.deserialize(serialized)

    assertThat(deserialized.blocks).hasSize(1)
    assertThat(deserialized.blocks[0]).isEqualTo(block)
  }

  @Test
  fun `can serialize and deserialize response with multiple blocks`() {
    val blocks =
      listOf(
        DataGenerators.randomSealedBeaconBlock(number = 100UL),
        DataGenerators.randomSealedBeaconBlock(number = 101UL),
        DataGenerators.randomSealedBeaconBlock(number = 102UL),
      )
    val response = BeaconBlocksByRangeResponse(blocks = blocks)

    val serialized = serDe.serialize(response)
    val deserialized = serDe.deserialize(serialized)

    assertThat(deserialized.blocks).hasSize(3)
    assertThat(deserialized.blocks).isEqualTo(blocks)
  }

  @Test
  fun `preserves block order during serialization`() {
    val blocks =
      (1UL..10UL).map { blockNumber ->
        DataGenerators.randomSealedBeaconBlock(number = blockNumber)
      }
    val response = BeaconBlocksByRangeResponse(blocks = blocks)

    val serialized = serDe.serialize(response)
    val deserialized = serDe.deserialize(serialized)

    assertThat(deserialized.blocks).hasSize(10)
    deserialized.blocks.forEachIndexed { index, block ->
      assertThat(block.beaconBlock.beaconBlockHeader.number).isEqualTo((index + 1).toULong())
    }
  }
}
