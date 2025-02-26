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

import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import maru.core.HashUtil
import maru.serialization.rlp.RLPCommitSealSerializers
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockInterface
import org.hyperledger.besu.consensus.qbft.core.types.QbftHashMode

/**
 * Adaptor class for QBFT block interface, this provides a way to replace the round number in a block
 */
class QbftBlockInterfaceAdaptor : QbftBlockInterface {
  override fun replaceRoundInBlock(
    proposalBlock: QbftBlock,
    roundNumber: Int,
    hashMode: QbftHashMode,
  ): QbftBlock {
    val beaconBlockHeader = BlockUtil.toBeaconBlockHeader(proposalBlock.header)
    val replacedBeaconBlockHeader =
      BeaconBlockHeader(
        number = beaconBlockHeader.number,
        round = roundNumber.toULong(),
        timestamp = beaconBlockHeader.timestamp,
        proposer = beaconBlockHeader.proposer,
        parentRoot = beaconBlockHeader.parentRoot,
        stateRoot = beaconBlockHeader.stateRoot,
        bodyRoot = beaconBlockHeader.bodyRoot,
        HashUtil.headerCommittedSealHash(RLPCommitSealSerializers.BeaconBlockHeaderSerializer),
      )

    return QbftBlockAdaptor(
      BeaconBlock(replacedBeaconBlockHeader, BlockUtil.toBeaconBlock(proposalBlock).beaconBlockBody),
    )
  }
}
