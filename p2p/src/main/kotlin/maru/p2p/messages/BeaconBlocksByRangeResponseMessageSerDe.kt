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
import maru.serialization.rlp.SealedBeaconBlockSerDe
import org.hyperledger.besu.ethereum.rlp.RLPInput
import org.hyperledger.besu.ethereum.rlp.RLPOutput

class BeaconBlocksByRangeResponseMessageSerDe(
  private val sealedBeaconBlockSerDe: SealedBeaconBlockSerDe,
  compressor: MaruCompressor = MaruSnappyFramedCompressor(),
) : MaruCompressorRLPSerDe<Message<BeaconBlocksByRangeResponse, RpcMessageType>>(compressor) {
  override fun writeTo(
    value: Message<BeaconBlocksByRangeResponse, RpcMessageType>,
    rlpOutput: RLPOutput,
  ) {
    rlpOutput.startList()
    rlpOutput.writeList(value.payload.blocks) { block, output ->
      sealedBeaconBlockSerDe.writeTo(block, output)
    }
    rlpOutput.endList()
  }

  override fun readFrom(rlpInput: RLPInput): Message<BeaconBlocksByRangeResponse, RpcMessageType> {
    rlpInput.enterList()
    val blocks = rlpInput.readList { sealedBeaconBlockSerDe.readFrom(it) }
    rlpInput.leaveList()

    return Message(
      RpcMessageType.BEACON_BLOCKS_BY_RANGE,
      Version.V1,
      BeaconBlocksByRangeResponse(blocks = blocks),
    )
  }
}
