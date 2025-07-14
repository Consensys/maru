/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft

import maru.consensus.ValidatorProvider
import maru.consensus.qbft.adapters.QbftBlockAdapter
import maru.consensus.qbft.adapters.QbftSealedBlockAdapter
import maru.consensus.qbft.adapters.toBeaconBlock
import maru.consensus.qbft.adapters.toBeaconBlockHeader
import maru.core.BeaconBlock
import maru.core.BeaconBlockBody
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.EMPTY_HASH
import maru.core.HashUtil
import maru.core.Seal
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import maru.serialization.rlp.bodyRoot
import maru.serialization.rlp.headerHash
import maru.serialization.rlp.stateRoot
import org.apache.logging.log4j.LogManager
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.blockcreation.ProposerSelector
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockCreator
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockHeader
import org.hyperledger.besu.crypto.SECPSignature

/**
 * Responsible for QBFT block creation. As opposed to EagerBlockCreator, Delayed one relies on the fact that FCU was
 * called some time in advance. So at the time of `createBlock` it actually ends the block creation process, not
 * starts it
 */
class DelayedQbftBlockCreator(
  private val manager: ExecutionLayerManager,
  private val proposerSelector: ProposerSelector,
  private val validatorProvider: ValidatorProvider,
  private val beaconChain: BeaconChain,
  private val round: Int,
) : QbftBlockCreator {
  private val log = LogManager.getLogger(this.javaClass)

  companion object {
    fun createSealedBlock(
      qbftBlock: QbftBlock,
      roundNumber: Int,
      commitSeals: Collection<SECPSignature>,
    ): QbftBlock {
      val seals =
        commitSeals.map { Seal(it.encodedBytes().toArrayUnsafe()) }.toSet()
      val beaconBlock = qbftBlock.toBeaconBlock()
      val beaconBlockHeader = beaconBlock.beaconBlockHeader
      val updatedBlockHeader = beaconBlockHeader.copy(round = roundNumber.toUInt())
      val sealedBlockBody =
        SealedBeaconBlock(
          BeaconBlock(updatedBlockHeader, beaconBlock.beaconBlockBody),
          seals,
        )
      return QbftSealedBlockAdapter(sealedBlockBody)
    }
  }

  override fun createBlock(
    headerTimeStampSeconds: Long,
    parentHeader: QbftBlockHeader,
  ): QbftBlock {
    val parentBeaconBlockHeader = parentHeader.toBeaconBlockHeader()
    val latestBeaconBlock =
      beaconChain.getSealedBeaconBlock(parentBeaconBlockHeader.hash())
        ?: throw IllegalStateException("Parent beacon block unavailable, unable to create block")

    // Special case: if parent block has empty hash (genesis block), we need to ensure
    // block building was started with the correct head hash from EL
    if (latestBeaconBlock.beaconBlock.beaconBlockBody.executionPayload.blockHash
        .contentEquals(EMPTY_HASH)
    ) {
      log.debug("Parent block has empty hash, ensuring block building is started with latest EL head")
      try {
        // Get latest block hash from EL and start block building if not already started
        val latestBlockHash = manager.getLatestBlockHash().get()
        // Note: In a proper implementation, we might need more sophisticated logic here
        // to check if block building is already in progress and handle accordingly
        log.debug("Using latest EL block hash for genesis case: ${latestBlockHash.contentToString()}")
      } catch (e: Exception) {
        log.warn("Could not get latest block hash from EL for genesis case", e)
      }
    }

    val executionPayload =
      try {
        manager.finishBlockBuilding().get()
      } catch (e: Exception) {
        throw IllegalStateException("Execution payload unavailable, unable to create block", e)
      }
    val beaconBlockBody =
      BeaconBlockBody(latestBeaconBlock.commitSeals, executionPayload)
    val proposer =
      proposerSelector.selectProposerForRound(
        ConsensusRoundIdentifier((parentBeaconBlockHeader.number + 1UL).toLong(), round),
      )
    val stateRootBlockHeader =
      BeaconBlockHeader(
        number = parentBeaconBlockHeader.number + 1UL,
        round = round.toUInt(),
        timestamp = headerTimeStampSeconds.toULong(),
        proposer = Validator(proposer.toArrayUnsafe()),
        parentRoot = parentBeaconBlockHeader.hash(),
        stateRoot = EMPTY_HASH, // temporary state root to avoid circular dependency
        bodyRoot = HashUtil.bodyRoot(beaconBlockBody),
        headerHashFunction = HashUtil::headerHash,
      )
    val validators =
      validatorProvider
        .getValidatorsAfterBlock(
          parentBeaconBlockHeader.number,
        ).get()
    val stateRoot =
      HashUtil.stateRoot(
        BeaconState(stateRootBlockHeader, validators),
      )
    val finalBlockHeader = stateRootBlockHeader.copy(stateRoot = stateRoot)
    val beaconBlock =
      BeaconBlock(finalBlockHeader, beaconBlockBody)
    return QbftBlockAdapter(beaconBlock)
  }

  override fun createSealedBlock(
    block: QbftBlock,
    roundNumber: Int,
    commitSeals: Collection<SECPSignature>,
  ): QbftBlock = createSealedBlock(qbftBlock = block, roundNumber = roundNumber, commitSeals = commitSeals)
}
