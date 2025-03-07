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
package maru.consensus.statetransition

import maru.consensus.SealVerifier
import maru.consensus.Spec
import maru.core.BeaconBlock
import maru.core.BeaconBlockBody
import maru.core.BeaconState
import maru.executionlayer.client.ExecutionLayerClient
import tech.pegasys.teku.infrastructure.async.SafeFuture

interface BlockValidator {
  sealed interface ValidationResult {
    data class Valid(
      val postBeaconState: BeaconState,
    ) : ValidationResult

    data class Invalid(
      val reason: String,
    ) : ValidationResult
  }

  fun validateBlock(
    block: BeaconBlock,
    preState: BeaconState,
  ): SafeFuture<ValidationResult>
}

class BlockValidatorImpl(
  private val spec: Spec,
  private val sealVerifier: SealVerifier,
  private val executionLayerClient: ExecutionLayerClient,
) : BlockValidator {
  override fun validateBlock(
    block: BeaconBlock,
    preState: BeaconState,
  ): SafeFuture<BlockValidator.ValidationResult> {
    if (block.beaconBlockHeader.number != preState.latestBeaconBlockHeader.number + 1u) {
      return SafeFuture.completedFuture(
        BlockValidator.ValidationResult.Invalid("Block number is not the next block number"),
      )
    }

    if (!block.beaconBlockHeader.parentRoot.contentEquals(preState.latestBeaconBlockRoot)) {
      return SafeFuture.completedFuture(BlockValidator.ValidationResult.Invalid("Parent root does not match"))
    }

    val beaconBlockBodyRoot = computeBeaconBlockBodyHash(block.beaconBlockBody)

    val postBeaconStateForRootCalculation =
      preState.copy(
        latestBeaconBlockHeader = block.beaconBlockHeader.copy(stateRoot = ByteArray(0)),
        latestBeaconBlockRoot = beaconBlockBodyRoot,
      )
    val stateRoot = computeBeaconStateHash(postBeaconStateForRootCalculation)

    if (!block.beaconBlockHeader.stateRoot.contentEquals(stateRoot)) {
      return SafeFuture.completedFuture(BlockValidator.ValidationResult.Invalid("State root does not match"))
    }

    val postBeaconState =
      preState.copy(
        latestBeaconBlockHeader = block.beaconBlockHeader,
        latestBeaconBlockRoot = beaconBlockBodyRoot,
      )

    if (!spec.isProposerExpectedProposer(block.beaconBlockHeader.proposer, postBeaconState)) {
      return SafeFuture.completedFuture(BlockValidator.ValidationResult.Invalid("Proposer is not expected proposer"))
    }

    for (seal in block.beaconBlockBody.prevBlockSeals) {
      if (!sealVerifier.verifySeal(seal, preState.latestBeaconBlockRoot)) {
        return SafeFuture.completedFuture(BlockValidator.ValidationResult.Invalid("Seal verification failed"))
      }
    }

    return executionLayerClient.newPayload(block.beaconBlockBody.executionPayload).thenApply {
      if (it.isSuccess && it.payload.asInternalExecutionPayload().hasValidStatus()) {
        BlockValidator.ValidationResult.Valid(postBeaconState)
      } else {
        BlockValidator.ValidationResult.Invalid("Execution payload validation failed")
      }
    }
  }

  private fun computeBeaconBlockBodyHash(beaconBlockBody: BeaconBlockBody): ByteArray =
    throw NotImplementedError("Not implemented")

  private fun computeBeaconStateHash(beaconState: BeaconState): ByteArray = throw NotImplementedError("Not implemented")
}
