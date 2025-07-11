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

class BeaconBlocksByRangeMessageSerDeTest {
  @Test
  fun `request message serDe wraps and unwraps correctly`() {
    val requestSerDe = BeaconBlocksByRangeRequestSerDe()
    val messageSerDe = BeaconBlocksByRangeRequestMessageSerDe(requestSerDe)

    val request =
      BeaconBlocksByRangeRequest(
        startBlockNumber = 150UL,
        count = 32UL,
      )
    val message =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = request,
      )

    val serialized = messageSerDe.serialize(message)
    val deserialized = messageSerDe.deserialize(serialized)

    assertThat(deserialized.type).isEqualTo(RpcMessageType.BEACON_BLOCKS_BY_RANGE)
    assertThat(deserialized.version).isEqualTo(Version.V1)
    assertThat(deserialized.payload).isEqualTo(request)
  }

  @Test
  fun `response message serDe wraps and unwraps correctly`() {
    val responseSerDe = BeaconBlocksByRangeResponseSerDe(RLPSerializers.SealedBeaconBlockSerializer)
    val messageSerDe = BeaconBlocksByRangeResponseMessageSerDe(responseSerDe)

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
  fun `request message serDe only serializes payload`() {
    val requestSerDe = BeaconBlocksByRangeRequestSerDe()
    val messageSerDe = BeaconBlocksByRangeRequestMessageSerDe(requestSerDe)

    val request =
      BeaconBlocksByRangeRequest(
        startBlockNumber = 200UL,
        count = 10UL,
      )
    val message =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = request,
      )

    val messageSerialized = messageSerDe.serialize(message)
    val payloadSerialized = requestSerDe.serialize(request)

    // Message serialization should only serialize the payload
    assertThat(messageSerialized).isEqualTo(payloadSerialized)
  }

  @Test
  fun `response message serDe only serializes payload`() {
    val responseSerDe = BeaconBlocksByRangeResponseSerDe(RLPSerializers.SealedBeaconBlockSerializer)
    val messageSerDe = BeaconBlocksByRangeResponseMessageSerDe(responseSerDe)

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

    val messageSerialized = messageSerDe.serialize(message)
    val payloadSerialized = responseSerDe.serialize(response)

    // Message serialization should only serialize the payload
    assertThat(messageSerialized).isEqualTo(payloadSerialized)
  }

  @Test
  fun `deserialize adds correct message type and version`() {
    val requestSerDe = BeaconBlocksByRangeRequestSerDe()
    val messageSerDe = BeaconBlocksByRangeRequestMessageSerDe(requestSerDe)

    val request =
      BeaconBlocksByRangeRequest(
        startBlockNumber = 1000UL,
        count = 1UL,
      )

    // Serialize just the payload
    val serialized = requestSerDe.serialize(request)

    // Deserialize should add message wrapper
    val message = messageSerDe.deserialize(serialized)

    assertThat(message.type).isEqualTo(RpcMessageType.BEACON_BLOCKS_BY_RANGE)
    assertThat(message.version).isEqualTo(Version.V1)
    assertThat(message.payload).isEqualTo(request)
  }
}
