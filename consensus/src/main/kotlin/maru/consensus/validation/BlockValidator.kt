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
package maru.consensus.validation

import maru.consensus.ValidatorProvider
import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import maru.core.HashUtil
import maru.database.Database
import maru.executionlayer.client.ExecutionLayerClient
import maru.executionlayer.extensions.hasValidExecutionPayload
import maru.serialization.rlp.bodyRoot
import tech.pegasys.teku.infrastructure.async.SafeFuture

interface BlockValidator {
  fun validateBlock(
    block: BeaconBlock,
    prevBlockHeader: BeaconBlockHeader,
  ): SafeFuture<Result<Boolean>>

  fun validateBlock(block: BeaconBlock): SafeFuture<Result<Boolean>>
}

class BlockValidationException : Exception {
  constructor(message: String) : super(message)
  constructor(message: String, cause: Throwable) : super(message, cause)
}

class BlockValidatorImpl(
  private val validatorProvider: ValidatorProvider,
  private val sealVerifier: SealVerifier,
  private val executionLayerClient: ExecutionLayerClient,
  private val database: Database,
) : BlockValidator {
  companion object {
    fun invalid(message: String): Result<Boolean> = Result.failure(BlockValidationException("Invalid block: $message"))

    fun invalid(
      message: String,
      cause: Throwable,
    ): Result<Boolean> =
      Result.failure(BlockValidationException("Invalid block: $message" + ", cause: ${cause.message}", cause))

    fun valid(): Result<Boolean> = Result.success(true)
  }

  override fun validateBlock(
    block: BeaconBlock,
    prevBlockHeader: BeaconBlockHeader,
  ): SafeFuture<Result<Boolean>> {
    if (block.beaconBlockHeader.number != prevBlockHeader.number + 1u) {
      return SafeFuture.completedFuture(
        invalid("Block number is not the next block number"),
      )
    }

    if (block.beaconBlockHeader.timestamp <= prevBlockHeader.timestamp) {
      return SafeFuture.completedFuture(
        invalid("Block timestamp is not greater than previous block timestamp"),
      )
    }

    if (validatorProvider.getProposerForBlock(block.beaconBlockHeader) != block.beaconBlockHeader.proposer) {
      return SafeFuture.completedFuture(invalid("Proposer is not expected proposer"))
    }

    if (!block.beaconBlockHeader.parentRoot.contentEquals(prevBlockHeader.hash)) {
      return SafeFuture.completedFuture(invalid("Parent root does not match"))
    }

    val beaconBodyRoot = HashUtil.bodyRoot(block.beaconBlockBody)

    if (!block.beaconBlockHeader.bodyRoot.contentEquals(beaconBodyRoot)) {
      return SafeFuture.completedFuture(invalid("Body root does not match"))
    }

    val prevBlockValidators = validatorProvider.getValidatorsForBlock(prevBlockHeader)
    if (block.beaconBlockBody.prevCommitSeals.size < Math.ceilDiv(prevBlockValidators.size, 2)) {
      return SafeFuture.completedFuture(
        invalid("Not enough commit seals for previous block"),
      )
    }

    for (seal in block.beaconBlockBody.prevCommitSeals) {
      val sealVerificationResult = sealVerifier.verifySeal(seal, prevBlockHeader)
      if (sealVerificationResult.isFailure) {
        return SafeFuture.completedFuture(
          invalid("Previous block seal verification failed", sealVerificationResult.exceptionOrNull()!!),
        )
      }
    }

    return executionLayerClient.newPayload(block.beaconBlockBody.executionPayload).thenApply { newPayloadResponse ->
      if (newPayloadResponse.isSuccess && newPayloadResponse.payload.hasValidExecutionPayload()) {
        valid()
      } else {
        invalid("Execution payload validation failed: ${newPayloadResponse.errorMessage}")
      }
    }
  }

  override fun validateBlock(block: BeaconBlock): SafeFuture<Result<Boolean>> {
    val prevSealedBeaconBlock = database.getSealedBeaconBlock(block.beaconBlockHeader.hash)
    return if (prevSealedBeaconBlock != null) {
      validateBlock(block, prevSealedBeaconBlock.beaconBlock.beaconBlockHeader)
    } else {
      SafeFuture.completedFuture(invalid("Previous block not found"))
    }
  }
}
