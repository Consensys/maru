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
import maru.p2p.Message
import maru.p2p.RpcMessageType
import maru.p2p.Version
import maru.serialization.rlp.RLPSerializers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BeaconBlocksByRangeResponseMessageSerDeTest {
  private val messageSerDe = BeaconBlocksByRangeResponseMessageSerDe(RLPSerializers.SealedBeaconBlockSerializer)

  @Test
  fun `response message serDe wraps and unwraps correctly`() {
    val blocks =
      listOf(
        DataGenerators.randomSealedBeaconBlock(number = 10UL),
        DataGenerators.randomSealedBeaconBlock(number = 11UL),
      )
    val response = BeaconBlocksByRangeResponse(blocks = blocks)
    val message =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = response,
      )

    val serialized = messageSerDe.serialize(message)
    val deserialized = messageSerDe.deserialize(serialized)

    assertThat(deserialized.type).isEqualTo(RpcMessageType.BEACON_BLOCKS_BY_RANGE)
    assertThat(deserialized.version).isEqualTo(Version.V1)
    assertThat(deserialized.payload.blocks).hasSize(2)
    assertThat(deserialized.payload.blocks[0]).isEqualTo(blocks[0])
    assertThat(deserialized.payload.blocks[1]).isEqualTo(blocks[1])
  }

  @Test
  fun `response message serDe serializes and deserializes correctly`() {
    val response =
      BeaconBlocksByRangeResponse(
        blocks = listOf(DataGenerators.randomSealedBeaconBlock(number = 5UL)),
      )
    val message =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = response,
      )

    val serialized = messageSerDe.serialize(message)
    val deserialized = messageSerDe.deserialize(serialized)

    assertThat(deserialized.type).isEqualTo(RpcMessageType.BEACON_BLOCKS_BY_RANGE)
    assertThat(deserialized.version).isEqualTo(Version.V1)
    assertThat(deserialized.payload.blocks).hasSize(1)
    assertThat(deserialized.payload).isEqualTo(response)
  }
}