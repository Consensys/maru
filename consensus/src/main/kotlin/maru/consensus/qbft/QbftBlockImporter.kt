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
import maru.consensus.qbft.adaptors.toSealedBeaconBlock
import maru.consensus.state.FinalizationState
import maru.consensus.state.StateTransition
import maru.consensus.state.StateTransition.StateTransitionError
import maru.core.BeaconState
import maru.database.Database
import maru.executionlayer.manager.ExecutionLayerManager
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockImporter

class QbftBlockImporter(
  private val blockchain: Database,
  private val executionLayerManager: ExecutionLayerManager,
  private val stateTransition: StateTransition,
  private val finalizationStateProvider: () -> FinalizationState,
) : QbftBlockImporter {
  override fun importBlock(qbftBlock: QbftBlock): Boolean {
    val sealedBeaconBlock = qbftBlock.toSealedBeaconBlock()

    blockchain.newUpdater().use { updater ->
      try {
        val currentState = blockchain.getLatestBeaconState()
        val resultingState: BeaconState =
          when (
            val resultingState =
              stateTransition
                .processBlock(
                  currentState,
                  sealedBeaconBlock.beaconBlock,
                ).get()
          ) {
            is Ok<BeaconState> -> resultingState.value
            is Err<StateTransitionError> -> return false
          }

        updater
          .putBeaconState(resultingState)
          .putSealedBeaconBlock(sealedBeaconBlock, sealedBeaconBlock.beaconBlock.beaconBlockHeader.bodyRoot)
        executionLayerManager
          .setHead(
            headHash = sealedBeaconBlock.beaconBlock.beaconBlockBody.executionPayload.blockHash,
            safeHash = finalizationStateProvider().safeBlockHash,
            finalizedHash = finalizationStateProvider().finalizedBlockHash,
            payloadAttributes = null,
          ).get()
      } catch (e: Exception) {
        updater.rollback()
        return false
      }
      updater.commit()
    }

    return true
  }
}
