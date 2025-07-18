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
import maru.serialization.SerDe

class LatestBeaconStateRequestMessageSerDe : SerDe<Message<LatestBeaconStateRequest, RpcMessageType>> {
  override fun serialize(value: Message<LatestBeaconStateRequest, RpcMessageType>): ByteArray {
    TODO("Not yet implemented")
  }

  override fun deserialize(bytes: ByteArray): Message<LatestBeaconStateRequest, RpcMessageType> {
    TODO("Not yet implemented")
  }
}
