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
import maru.consensus.state.StateTransition.Companion.createTransitionStateBeaconBlockHeader
import maru.consensus.toConsensusRoundIdentifier
import maru.consensus.validation.BlockValidator
import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.HashUtil
import maru.core.HeaderHashFunction
import maru.core.Validator
import maru.serialization.rlp.bodyRoot
import maru.serialization.rlp.stateRoot
import tech.pegasys.teku.infrastructure.async.SafeFuture

interface StateTransition {
  data class StateTransitionError(
    val message: String,
  )

  fun processBlock(
    preState: BeaconState,
    block: BeaconBlock,
  ): SafeFuture<Result<BeaconState, StateTransitionError>>

  companion object {
    fun createTransitionStateBeaconBlockHeader(
      number: ULong,
      round: ULong,
      timestamp: ULong,
      proposer: Validator,
      parentRoot: ByteArray,
      bodyRoot: ByteArray,
      headerHashFunction: HeaderHashFunction,
    ): BeaconBlockHeader =
      BeaconBlockHeader(
        number = number,
        round = round,
        timestamp = timestamp,
        proposer = proposer,
        parentRoot = parentRoot,
        stateRoot = ByteArray(0),
        bodyRoot = bodyRoot,
        headerHashFunction = headerHashFunction,
      )
  }
}

class StateTransitionImpl(
  private val blockValidator: BlockValidator,
  private val validatorProvider: ValidatorProvider,
  private val proposerSelector: ProposerSelector,
) : StateTransition {
  override fun processBlock(
    preState: BeaconState,
    block: BeaconBlock,
  ): SafeFuture<Result<BeaconState, StateTransition.StateTransitionError>> {
    val validatorsForBlockFuture = validatorProvider.getValidatorsForBlock(block.beaconBlockHeader)
    val proposerForBlockFuture =
      proposerSelector
        .getProposerForBlock(block.beaconBlockHeader.toConsensusRoundIdentifier())

    return validatorsForBlockFuture.thenComposeCombined(
      proposerForBlockFuture,
    ) { validatorsForBlock, proposerForBlock ->
      val beaconBodyRoot = HashUtil.bodyRoot(block.beaconBlockBody)
      val transitionStateBeaconBlockHeader =
        createTransitionStateBeaconBlockHeader(
          number = block.beaconBlockHeader.number,
          round = block.beaconBlockHeader.round,
          timestamp = block.beaconBlockHeader.timestamp,
          proposer = proposerForBlock,
          parentRoot = preState.latestBeaconBlockHeader.hash,
          bodyRoot = beaconBodyRoot,
          headerHashFunction = block.beaconBlockHeader.headerHashFunction,
        )
      val transitionState =
        preState.copy(
          latestBeaconBlockHeader = transitionStateBeaconBlockHeader,
          latestBeaconBlockRoot = beaconBodyRoot,
        )
      val stateRootHash = HashUtil.stateRoot(transitionState)
      val expectedNewBlockHeader = transitionStateBeaconBlockHeader.copy(stateRoot = stateRootHash)
      if (!expectedNewBlockHeader.stateRoot.contentEquals(block.beaconBlockHeader.stateRoot)) {
        SafeFuture.completedFuture(
          Err(
            StateTransition.StateTransitionError(
              "Beacon state root does not match. " +
                "Expected ${expectedNewBlockHeader.stateRoot.encodeHex()} " +
                "but got ${block.beaconBlockHeader.stateRoot.encodeHex()}",
            ),
          ),
        )
      } else {
        blockValidator
          .validateBlock(block, proposerForBlock, preState.latestBeaconBlockHeader)
          .thenApply { blockValidationResult ->
            when (blockValidationResult) {
              is Ok -> {
                val postState =
                  BeaconState(
                    latestBeaconBlockHeader = block.beaconBlockHeader,
                    latestBeaconBlockRoot = beaconBodyRoot,
                    validators = validatorsForBlock,
                  )
                Ok(postState)
              }
              is Err ->
                Err(
                  StateTransition.StateTransitionError(
                    "State Transition failed. " +
                      "Reason: ${blockValidationResult.error.message}",
                  ),
                )
            }
          }
      }
    }
  }
}
