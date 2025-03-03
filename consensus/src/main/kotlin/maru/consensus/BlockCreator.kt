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
import maru.core.HashType
import maru.core.HashUtil
import maru.core.Seal
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
      beaconChain.getBeaconBlock(parentHeader.hash())
        ?: throw IllegalStateException("Parent beacon block unavailable, unable to create block")

    val beaconBlockBody =
      BeaconBlockBody(latestBeaconBlock.beaconBlockBody.commitSeals, emptyList(), executionPayload)
    val bodyRoot = HashUtil.bodyRoot(beaconBlockBody)

    val number = parentHeader.number + 1UL
    val proposer =
      proposerSelector.selectProposerForRound(
        ConsensusRoundIdentifier(number.toLong(), roundNumber.toInt()),
      )
    val temporaryBlockHeader =
      BeaconBlockHeader(
        number.toULong(),
        roundNumber.toULong(),
        timestamp.toULong(),
        Validator(proposer.toArrayUnsafe()),
        parentHeader.hash(),
        ByteArray(32), // temporary state root to avoid circular dependency, will be replaced in final header
        bodyRoot,
        HashType.COMMITTED_SEAL.hashFunction,
      )

    val validators =
      validatorProvider
        .getValidatorsAfterBlock(
          parentHeader,
        )
    val stateRoot = HashUtil.stateRoot(BeaconState(temporaryBlockHeader, bodyRoot, validators))
    val finalBlockHeader = temporaryBlockHeader.copy(stateRoot = stateRoot)

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
  ): BeaconBlock {
    val beaconBlockHeader = block.beaconBlockHeader
    val sealedBlockHeader =
      BeaconBlockHeader(
        beaconBlockHeader.number,
        roundNumber.toULong(),
        beaconBlockHeader.timestamp,
        beaconBlockHeader.proposer,
        beaconBlockHeader.parentRoot,
        beaconBlockHeader.stateRoot,
        beaconBlockHeader.bodyRoot,
        HashType.ON_CHAIN.hashFunction,
      )

    val beaconBlockBody = block.beaconBlockBody
    val sealedBlockBody =
      BeaconBlockBody(
        beaconBlockBody.prevCommitSeals,
        commitSeals,
        beaconBlockBody.executionPayload,
      )

    return BeaconBlock(sealedBlockHeader, sealedBlockBody)
  }
}
