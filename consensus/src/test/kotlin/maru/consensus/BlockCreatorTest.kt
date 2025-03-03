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

import java.util.Collections
import kotlin.random.Random
import maru.core.BeaconState
import maru.core.HashUtil
import maru.core.Seal
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.blockcreation.ProposerSelector
import org.hyperledger.besu.datatypes.Address
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture

class BlockCreatorTest {
  private val executionLayerManager = Mockito.mock(ExecutionLayerManager::class.java)
  private val proposerSelector = Mockito.mock(ProposerSelector::class.java)
  private val validatorProvider = Mockito.mock(ValidatorProvider::class.java)
  private val beaconChain = Mockito.mock(BeaconChain::class.java)

  @Test
  fun `can create block`() {
    val parentBlock = DataGenerators.randomBeaconBlock(10U)
    val parentHeader = parentBlock.beaconBlockHeader
    val executionPayload = DataGenerators.randomExecutionPayload()
    whenever(beaconChain.getBeaconBlock(parentHeader.hash())).thenReturn(parentBlock)
    whenever(executionLayerManager.finishBlockBuilding()).thenReturn(SafeFuture.completedFuture(executionPayload))
    whenever(proposerSelector.selectProposerForRound(ConsensusRoundIdentifier(11L, 0))).thenReturn(Address.ZERO)

    val blockCreator = BlockCreator(executionLayerManager, proposerSelector, validatorProvider, beaconChain)
    val block = blockCreator.createBlock(1000L, 0, parentHeader)

    // block header fields
    val blockHeader = block!!.beaconBlockHeader
    assertThat(blockHeader.number).isEqualTo(11UL)
    assertThat(blockHeader.round).isEqualTo(0UL)
    assertThat(blockHeader.timestamp).isEqualTo(1000UL)
    assertThat(blockHeader.proposer).isEqualTo(Validator(Address.ZERO.toArray()))

    // block header roots
    val stateRoot =
      HashUtil.stateRoot(
        BeaconState(
          block.beaconBlockHeader.copy(stateRoot = ByteArray(32)),
          HashUtil.bodyRoot(block.beaconBlockBody),
          Collections.emptySet(),
        ),
      )
    assertThat(
      blockHeader.bodyRoot,
    ).isEqualTo(
      HashUtil.bodyRoot(block.beaconBlockBody),
    )
    assertThat(blockHeader.stateRoot).isEqualTo(stateRoot)
    assertThat(blockHeader.parentRoot).isEqualTo(parentHeader.hash())
    assertThat(block.beaconBlockHeader.hash()).isEqualTo(HashUtil.headerCommittedSealHash(block.beaconBlockHeader))

    // block body fields
    val blockBody = block.beaconBlockBody
    assertThat(
      blockBody.prevCommitSeals,
    ).isEqualTo(
      parentBlock.beaconBlockBody.commitSeals,
    )
    assertThat(blockBody.commitSeals).isEqualTo(Collections.emptyList<Seal>())
    assertThat(blockBody.executionPayload).isEqualTo(executionPayload)
  }

  @Test
  fun `can create block for round change`() {
    val parentBlock = DataGenerators.randomBeaconBlock(10U)
    val parentHeader = parentBlock.beaconBlockHeader
    val executionPayload = DataGenerators.randomExecutionPayload()
    val beaconState = DataGenerators.randomBeaconState(10U)
    val headHash = beaconState.latestBeaconBlockHeader.hash()
    whenever(beaconChain.getLatestBeaconState()).thenReturn(beaconState)
    whenever(beaconChain.getBeaconBlock(parentHeader.hash())).thenReturn(parentBlock)
    whenever(executionLayerManager.finishBlockBuilding()).thenReturn(SafeFuture.completedFuture(executionPayload))
    whenever(
      executionLayerManager.setHeadAndBuildBlockImmediately(headHash, headHash, headHash, 1000L),
    ).thenReturn(SafeFuture.completedFuture(executionPayload))
    whenever(proposerSelector.selectProposerForRound(ConsensusRoundIdentifier(11L, 1))).thenReturn(Address.ZERO)

    val blockCreator = BlockCreator(executionLayerManager, proposerSelector, validatorProvider, beaconChain)
    val block = blockCreator.createBlock(1000L, 1, parentHeader)

    // block header fields
    val blockHeader = block!!.beaconBlockHeader
    assertThat(blockHeader.number).isEqualTo(11UL)
    assertThat(blockHeader.round).isEqualTo(1UL)
    assertThat(blockHeader.timestamp).isEqualTo(1000UL)
    assertThat(blockHeader.proposer).isEqualTo(Validator(Address.ZERO.toArray()))

    // block header roots
    val stateRoot =
      HashUtil.stateRoot(
        BeaconState(
          block.beaconBlockHeader.copy(stateRoot = ByteArray(32)),
          HashUtil.bodyRoot(block.beaconBlockBody),
          Collections.emptySet(),
        ),
      )
    assertThat(
      blockHeader.bodyRoot,
    ).isEqualTo(
      HashUtil.bodyRoot(block.beaconBlockBody),
    )
    assertThat(blockHeader.stateRoot).isEqualTo(stateRoot)
    assertThat(blockHeader.parentRoot).isEqualTo(parentHeader.hash())
    assertThat(block.beaconBlockHeader.hash()).isEqualTo(HashUtil.headerCommittedSealHash(block.beaconBlockHeader))

    // block body fields
    val blockBody = block.beaconBlockBody
    assertThat(
      blockBody.prevCommitSeals,
    ).isEqualTo(
      parentBlock.beaconBlockBody.commitSeals,
    )
    assertThat(blockBody.commitSeals).isEqualTo(Collections.emptyList<Seal>())
    assertThat(blockBody.executionPayload).isEqualTo(executionPayload)
  }

  @Test
  fun `fails to create block if execution payload not available`() {
    val parentBlock = DataGenerators.randomBeaconBlock(10U)
    val parentHeader = parentBlock.beaconBlockHeader

    whenever(
      executionLayerManager.finishBlockBuilding(),
    ).thenReturn(SafeFuture.failedFuture(IllegalStateException("Execution payload not available")))

    val blockCreator = BlockCreator(executionLayerManager, proposerSelector, validatorProvider, beaconChain)
    assertThatThrownBy({
      blockCreator.createBlock(1000L, 0, parentHeader)
    })
      .isInstanceOf(
        IllegalStateException::class.java,
      ).hasMessage("Execution payload unavailable, unable to create block")
  }

  @Test
  fun `fails to create block if parent beacon block not available`() {
    val parentBlock = DataGenerators.randomBeaconBlock(10U)
    val parentHeader = parentBlock.beaconBlockHeader
    val executionPayload = DataGenerators.randomExecutionPayload()

    whenever(executionLayerManager.finishBlockBuilding()).thenReturn(SafeFuture.completedFuture(executionPayload))
    whenever(beaconChain.getBeaconBlock(parentHeader.hash())).thenReturn(null)
    whenever(proposerSelector.selectProposerForRound(ConsensusRoundIdentifier(11L, 0))).thenReturn(Address.ZERO)

    val blockCreator = BlockCreator(executionLayerManager, proposerSelector, validatorProvider, beaconChain)
    assertThatThrownBy({
      blockCreator.createBlock(1000L, 0, parentHeader)
    })
      .isInstanceOf(
        IllegalStateException::class.java,
      ).hasMessage("Parent beacon block unavailable, unable to create block")
  }

  @Test
  fun `can create sealed block`() {
    val block = DataGenerators.randomBeaconBlock(10U)
    val seals = (1..3).map { Seal(Random.nextBytes(96)) }
    val round = 1

    val blockCreator = BlockCreator(executionLayerManager, proposerSelector, validatorProvider, beaconChain)
    val sealedBlock = blockCreator.createSealedBlock(block, round, seals)

    // block header fields
    val sealedBlockHeader = sealedBlock.beaconBlockHeader
    assertThat(sealedBlockHeader.number).isEqualTo(block.beaconBlockHeader.number)
    assertThat(sealedBlockHeader.round).isEqualTo(round.toULong())
    assertThat(sealedBlockHeader.timestamp).isEqualTo(block.beaconBlockHeader.timestamp)
    assertThat(sealedBlockHeader.proposer).isEqualTo(block.beaconBlockHeader.proposer)

    // block header roots
    assertThat(
      sealedBlockHeader.bodyRoot,
    ).isEqualTo(
      block.beaconBlockHeader.bodyRoot,
    )
    assertThat(sealedBlockHeader.stateRoot).isEqualTo(block.beaconBlockHeader.stateRoot)
    assertThat(sealedBlockHeader.parentRoot).isEqualTo(block.beaconBlockHeader.parentRoot)
    assertThat(sealedBlockHeader.hash()).isEqualTo(HashUtil.headerOnChainHash(sealedBlock.beaconBlockHeader))

    // block body fields
    val sealedBlockBody = sealedBlock.beaconBlockBody
    assertThat(
      sealedBlockBody.prevCommitSeals,
    ).isEqualTo(
      block.beaconBlockBody.prevCommitSeals,
    )
    assertThat(sealedBlockBody.commitSeals).isEqualTo(seals)
    assertThat(sealedBlockBody.executionPayload).isEqualTo(block.beaconBlockBody.executionPayload)
  }
}
