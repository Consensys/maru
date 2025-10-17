/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import maru.p2p.RequestMessage
import maru.p2p.RpcMessageType
import maru.p2p.Version
import maru.serialization.rlp.RLPSerDe
import org.hyperledger.besu.ethereum.rlp.RLPInput
import org.hyperledger.besu.ethereum.rlp.RLPOutput

class BeaconBlocksByRangeRequestMessageSerDe(
  private val beaconBlocksByRangeRequestSerDe: RLPSerDe<BeaconBlocksByRangeRequest>,
) : RLPSerDe<RequestMessage<BeaconBlocksByRangeRequest, RpcMessageType>> {
  override fun writeTo(
    value: RequestMessage<BeaconBlocksByRangeRequest, RpcMessageType>,
    rlpOutput: RLPOutput,
  ) {
    beaconBlocksByRangeRequestSerDe.writeTo(value.payload, rlpOutput)
  }

  override fun readFrom(rlpInput: RLPInput): RequestMessage<BeaconBlocksByRangeRequest, RpcMessageType> =
    RequestMessage(
      RpcMessageType.BEACON_BLOCKS_BY_RANGE,
      Version.V1,
      beaconBlocksByRangeRequestSerDe.readFrom(rlpInput),
    )

  override fun serialize(value: RequestMessage<BeaconBlocksByRangeRequest, RpcMessageType>): ByteArray =
    beaconBlocksByRangeRequestSerDe.serialize(value.payload)

  override fun deserialize(bytes: ByteArray): RequestMessage<BeaconBlocksByRangeRequest, RpcMessageType> =
    RequestMessage(
      RpcMessageType.BEACON_BLOCKS_BY_RANGE,
      Version.V1,
      beaconBlocksByRangeRequestSerDe.deserialize(bytes),
    )
}
