/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import maru.p2p.ResponseMessage
import maru.p2p.RpcMessageType
import maru.p2p.Version
import maru.serialization.rlp.RLPSerDe
import org.hyperledger.besu.ethereum.rlp.RLPInput
import org.hyperledger.besu.ethereum.rlp.RLPOutput

class BeaconBlocksByRangeResponseMessageSerDe(
  private val beaconBlocksByRangeResponseSerDe: RLPSerDe<BeaconBlocksByRangeResponse>,
) : RLPSerDe<ResponseMessage<BeaconBlocksByRangeResponse, RpcMessageType>> {
  override fun writeTo(
    value: ResponseMessage<BeaconBlocksByRangeResponse, RpcMessageType>,
    rlpOutput: RLPOutput,
  ) {
    beaconBlocksByRangeResponseSerDe.writeTo(value.payload, rlpOutput)
  }

  override fun readFrom(rlpInput: RLPInput): ResponseMessage<BeaconBlocksByRangeResponse, RpcMessageType> =
    ResponseMessage(
      RpcMessageType.BEACON_BLOCKS_BY_RANGE,
      Version.V1,
      beaconBlocksByRangeResponseSerDe.readFrom(rlpInput),
    )

  override fun serialize(value: ResponseMessage<BeaconBlocksByRangeResponse, RpcMessageType>): ByteArray =
    beaconBlocksByRangeResponseSerDe.serialize(value.payload)

  override fun deserialize(bytes: ByteArray): ResponseMessage<BeaconBlocksByRangeResponse, RpcMessageType> =
    ResponseMessage(
      RpcMessageType.BEACON_BLOCKS_BY_RANGE,
      Version.V1,
      beaconBlocksByRangeResponseSerDe.deserialize(bytes),
    )
}
