/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.serialization.rlp

import maru.compression.MaruCompressor
import maru.core.SealedBeaconBlock
import maru.serialization.compression.MaruSnappyFramedCompressor
import org.hyperledger.besu.ethereum.rlp.RLPInput
import org.hyperledger.besu.ethereum.rlp.RLPOutput

class SealedBeaconBlockCompressorSerDe(
  private val sealedBeaconBlockSerializer: SealedBeaconBlockSerDe,
  private val compressor: MaruCompressor = MaruSnappyFramedCompressor(),
) : MaruCompressorRLPSerDe<SealedBeaconBlock>(compressor) {
  override fun writeTo(
    value: SealedBeaconBlock,
    rlpOutput: RLPOutput,
  ) {
    sealedBeaconBlockSerializer.writeTo(value, rlpOutput)
  }

  override fun readFrom(rlpInput: RLPInput): SealedBeaconBlock = sealedBeaconBlockSerializer.readFrom(rlpInput)
}
