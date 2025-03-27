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
package maru.consensus.state

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import encodeHex
import maru.consensus.ProposerSelector
import maru.consensus.ValidatorProvider
import maru.core.BeaconBlock
import maru.core.BeaconState
import maru.core.HashUtil
import maru.database.BeaconChain
import maru.serialization.rlp.bodyRoot
import maru.serialization.rlp.stateRoot
import tech.pegasys.teku.infrastructure.async.SafeFuture

interface StateTransition {
  data class StateTransitionError(
    val message: String,
  )

  fun processBlock(block: BeaconBlock): SafeFuture<Result<BeaconState, StateTransitionError>>
}

class StateTransitionImpl(
  private val beaconChain: BeaconChain,
  private val validatorProvider: ValidatorProvider,
  private val proposerSelector: ProposerSelector,
) : StateTransition {
  override fun processBlock(
    block: BeaconBlock,
  ): SafeFuture<Result<BeaconState, StateTransition.StateTransitionError>> {
    val preState = beaconChain.getLatestBeaconState()
    val validatorsForBlockFuture = validatorProvider.getValidatorsForBlock(block.beaconBlockHeader.number)
    val proposerForBlockFuture = proposerSelector.getProposerForBlock(block.beaconBlockHeader)

    return validatorsForBlockFuture.thenComposeCombined(
      proposerForBlockFuture,
    ) { validatorsForBlock, proposerForBlock ->
      val beaconBodyRoot = HashUtil.bodyRoot(block.beaconBlockBody)
      val tmpExpectedNewBlockHeader =
        block.beaconBlockHeader.copy(
          number = preState.latestBeaconBlockHeader.number + 1u,
          round = block.beaconBlockHeader.round,
          timestamp = block.beaconBlockHeader.timestamp,
          proposer = proposerForBlock,
          parentRoot = preState.latestBeaconBlockHeader.hash,
          stateRoot = ByteArray(0),
          bodyRoot = beaconBodyRoot,
        )
      val tmpState =
        BeaconState(
          latestBeaconBlockHeader = tmpExpectedNewBlockHeader,
          latestBeaconBlockRoot = beaconBodyRoot,
          validators = validatorsForBlock,
        )
      val stateRootHash = HashUtil.stateRoot(tmpState)
      if (!stateRootHash.contentEquals(block.beaconBlockHeader.stateRoot)) {
        SafeFuture.completedFuture(
          Err(
            StateTransition.StateTransitionError(
              "Beacon state root does not match. " +
                "Expected ${stateRootHash.encodeHex()} " +
                "but got ${block.beaconBlockHeader.stateRoot.encodeHex()}",
            ),
          ),
        )
      } else {
        val postState =
          BeaconState(
            latestBeaconBlockHeader = block.beaconBlockHeader,
            latestBeaconBlockRoot = beaconBodyRoot,
            validators = validatorsForBlock,
          )
        SafeFuture.completedFuture(Ok(postState))
      }
    }
  }
}
