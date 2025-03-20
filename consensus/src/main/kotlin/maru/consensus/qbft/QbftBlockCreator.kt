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
package maru.consensus.qbft

import maru.consensus.ValidatorProvider
import maru.consensus.bodyRoot
import maru.consensus.headerHash
import maru.consensus.qbft.adaptors.QbftBlockAdaptor
import maru.consensus.qbft.adaptors.QbftSealedBlockAdaptor
import maru.consensus.qbft.adaptors.toBeaconBlock
import maru.consensus.qbft.adaptors.toBeaconBlockHeader
import maru.consensus.stateRoot
import maru.core.BeaconBlock
import maru.core.BeaconBlockBody
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.HashUtil
import maru.core.Seal
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.blockcreation.ProposerSelector
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockCreator
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockHeader
import org.hyperledger.besu.crypto.SECPSignature

/**
 * Responsible for QBFT block creation.
 */
class QbftBlockCreator(
  private val manager: ExecutionLayerManager,
  private val proposerSelector: ProposerSelector,
  private val validatorProvider: ValidatorProvider,
  private val beaconChain: BeaconChain,
  private val round: Int,
) : QbftBlockCreator {
  override fun createBlock(
    headerTimeStampSeconds: Long,
    parentHeader: QbftBlockHeader,
  ): QbftBlock {
    val parentBeaconBlockHeader = parentHeader.toBeaconBlockHeader()
    val executionPayload =
      try {
        manager.finishBlockBuilding().get()
      } catch (e: Exception) {
        throw IllegalStateException("Execution payload unavailable, unable to create block", e)
      }
    val latestBeaconBlock =
      beaconChain.getSealedBeaconBlock(parentBeaconBlockHeader.hash())
        ?: throw IllegalStateException("Parent beacon block unavailable, unable to create block")
    val beaconBlockBody =
      BeaconBlockBody(latestBeaconBlock.commitSeals, executionPayload)
    val proposer =
      proposerSelector.selectProposerForRound(
        ConsensusRoundIdentifier((parentBeaconBlockHeader.number + 1UL).toLong(), round.toInt()),
      )
    val stateRootBlockHeader =
      BeaconBlockHeader(
        number = parentBeaconBlockHeader.number + 1UL,
        round = round.toULong(),
        timestamp = headerTimeStampSeconds.toULong(),
        proposer = Validator(proposer.toArrayUnsafe()),
        parentRoot = parentBeaconBlockHeader.hash(),
        stateRoot = ByteArray(32), // temporary state root to avoid circular dependency
        bodyRoot = HashUtil.bodyRoot(beaconBlockBody),
        headerHashFunction = HashUtil::headerHash,
      )
    val validators =
      validatorProvider
        .getValidatorsAfterBlock(
          parentBeaconBlockHeader.number,
        )
    val stateRoot =
      HashUtil.stateRoot(
        BeaconState(stateRootBlockHeader, HashUtil.bodyRoot(beaconBlockBody), validators),
      )
    val finalBlockHeader = stateRootBlockHeader.copy(stateRoot = stateRoot)
    val beaconBlock =
      BeaconBlock(finalBlockHeader, beaconBlockBody)
    return QbftBlockAdaptor(beaconBlock)
  }

  override fun createSealedBlock(
    block: QbftBlock,
    roundNumber: Int,
    commitSeals: Collection<SECPSignature>,
  ): QbftBlock {
    val seals =
      commitSeals.map {
        Seal(it.encodedBytes().toArrayUnsafe())
      }
    val block1 = block.toBeaconBlock()
    val beaconBlockHeader = block1.beaconBlockHeader
    val updatedBlockHeader = beaconBlockHeader.copy(round = roundNumber.toULong())
    val sealedBlockBody =
      SealedBeaconBlock(
        BeaconBlock(updatedBlockHeader, block1.beaconBlockBody),
        seals,
      )
    val sealedBeaconBlock = sealedBlockBody
    return QbftSealedBlockAdaptor(sealedBeaconBlock)
  }
}
