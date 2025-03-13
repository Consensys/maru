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

import maru.consensus.validation.BlockValidator
import maru.core.BeaconBlock
import maru.core.BeaconState
import maru.core.HashUtil
import maru.serialization.rlp.bodyRoot
import maru.serialization.rlp.stateRoot
import tech.pegasys.teku.infrastructure.async.SafeFuture

interface StateTransition {
  fun processBlock(
    block: BeaconBlock,
    preState: BeaconState,
  ): SafeFuture<Result<BeaconState>>
}

class StateTransitionException : Exception {
  constructor(message: String) : super(message)
  constructor(message: String, cause: Throwable) : super(message, cause)
}

class StateTransitionImpl(
  private val blockValidator: BlockValidator,
) : StateTransition {
  companion object {
    fun failure(message: String): Result<BeaconState> =
      Result.failure(StateTransitionException("State Transition failed: $message"))

    fun failure(
      message: String,
      cause: Throwable,
    ): Result<BeaconState> =
      Result.failure(StateTransitionException("State Transition failed: $message" + ", cause: ${cause.message}", cause))

    fun success(postState: BeaconState) = Result.success(postState)
  }

  override fun processBlock(
    block: BeaconBlock,
    preState: BeaconState,
  ): SafeFuture<Result<BeaconState>> {
    val stateRootBlockHeader =
      block.beaconBlockHeader.copy(
        stateRoot = ByteArray(0),
      )
    val beaconBodyRoot = HashUtil.bodyRoot(block.beaconBlockBody)
    val tmpState =
      preState.copy(
        latestBeaconBlockHeader = stateRootBlockHeader,
        latestBeaconBlockRoot = beaconBodyRoot,
      )

    if (!HashUtil.stateRoot(tmpState).contentEquals(block.beaconBlockHeader.stateRoot)) {
      return SafeFuture.completedFuture(failure("Beacon state root does not match"))
    }

    return blockValidator
      .validateBlock(block, preState.latestBeaconBlockHeader)
      .thenApply { blockValidationResult ->
        if (blockValidationResult.isFailure) {
          failure("Block validation failed", blockValidationResult.exceptionOrNull()!!)
        } else {
          val postState =
            preState.copy(
              latestBeaconBlockHeader = block.beaconBlockHeader,
              latestBeaconBlockRoot = beaconBodyRoot,
            )
          success(postState)
        }
      }
  }
}
