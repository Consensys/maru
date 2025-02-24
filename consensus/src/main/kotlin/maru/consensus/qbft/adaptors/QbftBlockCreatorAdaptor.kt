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

import maru.consensus.BlockCreator
import maru.core.Seal
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockCreator
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockHeader
import org.hyperledger.besu.consensus.qbft.core.types.QbftExtraDataProvider
import org.hyperledger.besu.crypto.SECPSignature

class QbftBlockCreatorAdaptor(
  private val blockCreator: BlockCreator,
  private val round: Int,
) : QbftBlockCreator {
  override fun createBlock(
    headerTimeStampSeconds: Long,
    parentHeader: QbftBlockHeader,
  ): QbftBlock? {
    val beaconBlock =
      blockCreator.createBlock(
        headerTimeStampSeconds,
        round,
        BlockUtil.toBeaconBlockHeader(parentHeader),
      )
    return beaconBlock?.let { QbftBlockAdaptor(it) }
  }

  override fun createSealedBlock(
    qbftExtraDataProvider: QbftExtraDataProvider,
    block: QbftBlock,
    roundNumber: Int,
    commitSeals: Collection<SECPSignature>,
  ): QbftBlock {
    val seals =
      commitSeals.map {
        Seal(it.encodedBytes().toArrayUnsafe())
      }
    val beaconBlock = blockCreator.createSealedBlock(BlockUtil.toBeaconBlock(block), roundNumber, seals)
    return QbftBlockAdaptor(beaconBlock)
  }
}
