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
import maru.consensus.ValidatorProvider
import maru.consensus.state.StateTransition.Companion.ok
import maru.core.BeaconBlock
import maru.core.BeaconState
import tech.pegasys.teku.infrastructure.async.SafeFuture

fun interface StateTransition {
  data class StateTransitionError(
    val message: String,
  )

  companion object {
    fun ok(beaconState: BeaconState): Result<BeaconState, StateTransitionError> = Ok(beaconState)

    fun error(message: String): Result<BeaconState, StateTransitionError> = Err(StateTransitionError(message))
  }

  fun processBlock(block: BeaconBlock): SafeFuture<Result<BeaconState, StateTransitionError>>
}

class StateTransitionImpl(
  private val validatorProvider: ValidatorProvider,
) : StateTransition {
  override fun processBlock(
    block: BeaconBlock,
  ): SafeFuture<Result<BeaconState, StateTransition.StateTransitionError>> {
    val validatorsForBlockFuture = validatorProvider.getValidatorsForBlock(block.beaconBlockHeader.number)
    return validatorsForBlockFuture.thenApply { validatorsForBlock ->
      val postState =
        BeaconState(
          latestBeaconBlockHeader = block.beaconBlockHeader,
          validators = validatorsForBlock,
        )
      ok(postState)
    }
  }
}
