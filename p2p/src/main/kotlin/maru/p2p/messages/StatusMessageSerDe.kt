/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import maru.compression.MaruCompressor
import maru.p2p.Message
import maru.p2p.RpcMessageType
import maru.p2p.Version
import maru.serialization.compression.MaruNoOpCompressor
import maru.serialization.rlp.MaruCompressorRLPSerDe
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.ethereum.rlp.RLPInput
import org.hyperledger.besu.ethereum.rlp.RLPOutput

class StatusMessageSerDe(
  compressor: MaruCompressor = MaruNoOpCompressor(),
) : MaruCompressorRLPSerDe<Message<Status, RpcMessageType>>(compressor) {
  override fun writeTo(
    value: Message<Status, RpcMessageType>,
    rlpOutput: RLPOutput,
  ) {
    rlpOutput.startList()

    rlpOutput.writeBytes(Bytes.wrap(value.payload.forkIdHash))
    rlpOutput.writeBytes(Bytes.wrap(value.payload.latestStateRoot))
    rlpOutput.writeLong(value.payload.latestBlockNumber.toLong())

    rlpOutput.endList()
  }

  override fun readFrom(rlpInput: RLPInput): Message<Status, RpcMessageType> {
    rlpInput.enterList()

    val forkId = rlpInput.readBytes().toArray()
    val headStateRoot = rlpInput.readBytes().toArray()
    val headBlockNumber = rlpInput.readLong().toULong()

    rlpInput.leaveList()

    return Message(
      RpcMessageType.STATUS,
      Version.V1,
      Status(forkIdHash = forkId, latestStateRoot = headStateRoot, latestBlockNumber = headBlockNumber),
    )
  }
}
