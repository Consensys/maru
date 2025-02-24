/*
   Copyright 2025 Consensys Software Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package maru.consensus.qbft.adaptors

import maru.consensus.qbft.adaptors.BlockUtil.toBeaconBlock
import maru.serialization.rlp.BeaconBlockBodySerializer
import maru.serialization.rlp.BeaconBlockHeaderSerializer
import maru.serialization.rlp.BeaconBlockSerializer
import maru.serialization.rlp.ExecutionPayloadSerializer
import maru.serialization.rlp.SealSerializer
import maru.serialization.rlp.ValidatorSerializer
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockCodec
import org.hyperledger.besu.consensus.qbft.core.types.QbftHashMode
import org.hyperledger.besu.ethereum.rlp.RLPInput
import org.hyperledger.besu.ethereum.rlp.RLPOutput

/**
 * Adaptor for [QbftBlockCodec], this provides a way to serialize QBFT blocks
 */
class QbftBlockCodecAdaptor : QbftBlockCodec {
  private val serializer =
    BeaconBlockSerializer(
      beaconBlockHeaderSerializer =
        BeaconBlockHeaderSerializer(
          validatorSerializer = ValidatorSerializer(),
        ),
      beaconBlockBodySerializer =
        BeaconBlockBodySerializer(
          sealSerializer = SealSerializer(),
          executionPayloadSerializer = ExecutionPayloadSerializer(),
        ),
    )

  override fun readFrom(
    rlpInput: RLPInput,
    qbftHashMode: QbftHashMode,
  ): QbftBlock = QbftBlockAdaptor(serializer.readFrom(rlpInput, HashFunctionUtils.toHashType(qbftHashMode)))

  override fun writeTo(
    qbftBlock: QbftBlock,
    rlpOutput: RLPOutput,
  ) {
    toBeaconBlock(qbftBlock).let {
      serializer.writeTo(it, rlpOutput)
    }
  }
}
