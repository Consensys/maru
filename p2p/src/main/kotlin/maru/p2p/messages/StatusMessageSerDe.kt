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
import maru.p2p.ResponseMessage
import maru.p2p.RpcMessageType
import maru.p2p.Version
import maru.serialization.rlp.RLPSerDe
import org.hyperledger.besu.ethereum.rlp.RLPInput
import org.hyperledger.besu.ethereum.rlp.RLPOutput

class StatusResponseMessageSerDe(
  val statusSerDe: RLPSerDe<Status>,
) : RLPSerDe<ResponseMessage<Status, RpcMessageType>> {
  override fun writeTo(
    value: ResponseMessage<Status, RpcMessageType>,
    rlpOutput: RLPOutput,
  ) {
    statusSerDe.writeTo(value.payload, rlpOutput)
  }

  override fun readFrom(rlpInput: RLPInput): ResponseMessage<Status, RpcMessageType> {
    val status = statusSerDe.readFrom(rlpInput)
    return ResponseMessage(
      RpcMessageType.STATUS,
      Version.V1,
      status,
    )
  }
}

class StatusRequestMessageSerDe(
  val statusSerDe: RLPSerDe<Status>,
) : RLPSerDe<RequestMessage<Status, RpcMessageType>> {
  override fun writeTo(
    value: RequestMessage<Status, RpcMessageType>,
    rlpOutput: RLPOutput,
  ) {
    statusSerDe.writeTo(value.payload, rlpOutput)
  }

  override fun readFrom(rlpInput: RLPInput): RequestMessage<Status, RpcMessageType> {
    val status = statusSerDe.readFrom(rlpInput)
    return RequestMessage(
      RpcMessageType.STATUS,
      Version.V1,
      status,
    )
  }
}
