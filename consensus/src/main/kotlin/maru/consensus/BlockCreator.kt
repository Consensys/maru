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
package maru.consensus

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
import org.apache.logging.log4j.LogManager
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.blockcreation.ProposerSelector
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockCreator

/**
 * Responsible for beacon block creation.
 */
class BlockCreator(
  private val manager: ExecutionLayerManager,
  private val proposerSelector: ProposerSelector,
  private val validatorProvider: ValidatorProvider,
  private val beaconChain: BeaconChain,
) {
  private val log = LogManager.getLogger(QbftBlockCreator::class.java)

  /**
   * Creates a new block with the given timestamp on of the parent header including the execution payload ready
   * to be proposed as a block in the QBFT consensus.
   */
  fun createBlock(
    timestamp: Long,
    roundNumber: Int,
    parentHeader: BeaconBlockHeader,
  ): BeaconBlock {
    val executionPayload =
      try {
        manager.finishBlockBuilding().get()
      } catch (e: Exception) {
        throw IllegalStateException("Execution payload unavailable, unable to create block", e)
      }
    val latestBeaconBlock =
      beaconChain.getSealedBeaconBlock(parentHeader.hash())
        ?: throw IllegalStateException("Parent beacon block unavailable, unable to create block")

    val beaconBlockBody =
      BeaconBlockBody(latestBeaconBlock.commitSeals, executionPayload)
    val proposer =
      proposerSelector.selectProposerForRound(
        ConsensusRoundIdentifier((parentHeader.number + 1UL).toLong(), roundNumber.toInt()),
      )
    val stateRootBlockHeader =
      BeaconBlockHeader(
        number = parentHeader.number + 1UL,
        round = roundNumber.toULong(),
        timestamp = timestamp.toULong(),
        proposer = Validator(proposer.toArrayUnsafe()),
        parentRoot = parentHeader.hash(),
        stateRoot = ByteArray(32), // temporary state root to avoid circular dependency
        bodyRoot = HashUtil.bodyRoot(beaconBlockBody),
        headerHashFunction = HashUtil::headerHash,
      )

    val validators =
      validatorProvider
        .getValidatorsAfterBlock(
          parentHeader.number,
        )
    val stateRoot =
      HashUtil.stateRoot(
        BeaconState(stateRootBlockHeader, HashUtil.bodyRoot(beaconBlockBody), validators),
      )
    val finalBlockHeader = stateRootBlockHeader.copy(stateRoot = stateRoot)

    return BeaconBlock(finalBlockHeader, beaconBlockBody)
  }

  /**
   * Creates a sealed block ready to be imported into the blockchain. This means including the commit seals
   * and round number and replacing the block hash with the onchain hash function.
   */
  fun createSealedBlock(
    block: BeaconBlock,
    roundNumber: Int,
    commitSeals: List<Seal>,
  ): SealedBeaconBlock {
    val beaconBlockHeader = block.beaconBlockHeader
    val updatedBlockHeader = beaconBlockHeader.copy(round = roundNumber.toULong())
    val sealedBlockBody =
      SealedBeaconBlock(
        BeaconBlock(updatedBlockHeader, block.beaconBlockBody),
        commitSeals,
      )
    return sealedBlockBody
  }
}
