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
import maru.serialization.compression.MaruSnappyFramedCompressor
import maru.serialization.rlp.MaruCompressorRLPSerDe
import org.hyperledger.besu.ethereum.rlp.RLPInput
import org.hyperledger.besu.ethereum.rlp.RLPOutput

class BeaconBlocksByRangeRequestMessageSerDe(
  compressor: MaruCompressor = MaruSnappyFramedCompressor(),
) : MaruCompressorRLPSerDe<Message<BeaconBlocksByRangeRequest, RpcMessageType>>(compressor) {
  override fun writeTo(
    value: Message<BeaconBlocksByRangeRequest, RpcMessageType>,
    rlpOutput: RLPOutput,
  ) {
    rlpOutput.startList()
    rlpOutput.writeLong(value.payload.startBlockNumber.toLong())
    rlpOutput.writeLong(value.payload.count.toLong())
    rlpOutput.endList()
  }

  override fun readFrom(rlpInput: RLPInput): Message<BeaconBlocksByRangeRequest, RpcMessageType> {
    rlpInput.enterList()
    val startBlockNumber = rlpInput.readLong().toULong()
    val count = rlpInput.readLong().toULong()
    rlpInput.leaveList()

    return Message(
      RpcMessageType.BEACON_BLOCKS_BY_RANGE,
      Version.V1,
      BeaconBlocksByRangeRequest(
        startBlockNumber = startBlockNumber,
        count = count,
      ),
    )
  }
}
