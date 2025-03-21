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

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import maru.consensus.NextBlockTimestampProvider
import maru.consensus.ProposerSelector
import maru.consensus.qbft.adaptors.toSealedBeaconBlock
import maru.consensus.state.FinalizationState
import maru.consensus.state.StateTransition
import maru.consensus.state.StateTransition.StateTransitionError
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.Validator
import maru.database.Database
import maru.executionlayer.manager.BlockMetadata
import maru.executionlayer.manager.ExecutionLayerManager
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockImporter

class QbftBlockImporter(
  private val blockchain: Database,
  private val executionLayerManager: ExecutionLayerManager,
  private val stateTransition: StateTransition,
  private val finalizationStateProvider: () -> FinalizationState,
  private val proposerSelector: ProposerSelector,
  private val nextBlockTimestampProvider: NextBlockTimestampProvider,
  private val latestBlockMetadataProvider: () -> BlockMetadata,
  private val blockBuilderIdentity: Validator,
) : QbftBlockImporter {
  override fun importBlock(qbftBlock: QbftBlock): Boolean {
    val sealedBeaconBlock = qbftBlock.toSealedBeaconBlock()
    val beaconBlock = sealedBeaconBlock.beaconBlock

    blockchain.newUpdater().use { updater ->
      try {
        val currentState = blockchain.getLatestBeaconState()
        val resultingState: BeaconState =
          when (
            val resultingState =
              stateTransition
                .processBlock(
                  currentState,
                  beaconBlock,
                ).get()
          ) {
            is Ok<BeaconState> -> resultingState.value
            is Err<StateTransitionError> -> return false
          }

        val beaconBlockHeader = beaconBlock.beaconBlockHeader
        updater
          .putBeaconState(resultingState)
          .putSealedBeaconBlock(sealedBeaconBlock, beaconBlockHeader.bodyRoot)
        val finalizationState = finalizationStateProvider()
        if (shouldBuildNextBlock(beaconBlockHeader)) {
          executionLayerManager.setHeadAndStartBlockBuilding(
            headHash = beaconBlock.beaconBlockBody.executionPayload.blockHash,
            safeHash = finalizationState.safeBlockHash,
            finalizedHash = finalizationState.finalizedBlockHash,
            nextBlockTimestamp =
              nextBlockTimestampProvider.nextTargetBlockUnixTimestamp(
                latestBlockMetadataProvider(),
              ),
            feeRecipient = blockBuilderIdentity.address,
          )
        } else {
          executionLayerManager
            .setHead(
              headHash = beaconBlock.beaconBlockBody.executionPayload.blockHash,
              safeHash = finalizationState.safeBlockHash,
              finalizedHash = finalizationState.finalizedBlockHash,
            ).get()
        }
      } catch (e: Exception) {
        updater.rollback()
        return false
      }
      updater.commit()
    }

    return true
  }

  private fun shouldBuildNextBlock(blockHeader: BeaconBlockHeader): Boolean {
    return false
    // proposerSelector.getProposerForBlock(ConsensusRoundIdentifier(blockHeader.number.toLong() + 1, 0)).get() ==
    // blockBuilderIdentity && it's time to propose next block
  }
}
