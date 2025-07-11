/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import maru.p2p.Message
import maru.p2p.RpcMessageType
import maru.p2p.Version
import maru.serialization.SerDe

class BeaconBlocksByRangeResponseMessageSerDe(
  private val responseSerDe: SerDe<BeaconBlocksByRangeResponse>,
) : SerDe<Message<BeaconBlocksByRangeResponse, RpcMessageType>> {
  override fun serialize(value: Message<BeaconBlocksByRangeResponse, RpcMessageType>): ByteArray =
    responseSerDe.serialize(value.payload)

  override fun deserialize(bytes: ByteArray): Message<BeaconBlocksByRangeResponse, RpcMessageType> =
    Message(RpcMessageType.BEACON_BLOCKS_BY_RANGE, Version.V1, responseSerDe.deserialize(bytes))
}
