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

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import encodeHex
import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import maru.core.HashUtil
import maru.core.Validator
import maru.executionlayer.client.ExecutionLayerClient
import maru.executionlayer.extensions.hasValidExecutionPayload
import maru.serialization.rlp.bodyRoot
import org.hyperledger.besu.consensus.common.bft.BftHelpers
import tech.pegasys.teku.infrastructure.async.SafeFuture

fun interface BlockValidator {
  data class BlockValidationError(
    val message: String,
  )

  fun validateBlock(
    newBlock: BeaconBlock,
    proposerForNewBlock: Validator,
    prevBlockHeader: BeaconBlockHeader,
    validatorsForPrevBlock: Set<Validator>,
  ): SafeFuture<Result<Boolean, BlockValidationError>>
}

class CompositeBlockValidator(
  private val blockValidators: List<BlockValidator>,
) : BlockValidator {
  override fun validateBlock(
    newBlock: BeaconBlock,
    proposerForNewBlock: Validator,
    prevBlockHeader: BeaconBlockHeader,
    validatorsForPrevBlock: Set<Validator>,
  ): SafeFuture<Result<Boolean, BlockValidator.BlockValidationError>> {
    val validationResultFutures =
      blockValidators
        .map { it.validateBlock(newBlock, proposerForNewBlock, prevBlockHeader, validatorsForPrevBlock) }
        .stream()
    return SafeFuture.collectAll(validationResultFutures).thenApply { validationResults ->
      val errors = validationResults.mapNotNull { it.component2() }
      if (errors.isEmpty()) {
        Ok(true)
      } else {
        Err(BlockValidator.BlockValidationError(errors.joinToString { it.message }))
      }
    }
  }
}

object BlockValidators {
  val BlockNumberValidator =
    BlockValidator { block, _, prevBlockHeader, _ ->
      if (block.beaconBlockHeader.number != prevBlockHeader.number + 1u) {
        SafeFuture.completedFuture(
          Err(
            BlockValidator.BlockValidationError(
              "Block number (${block.beaconBlockHeader.number}) " +
                "is not the next block number (${prevBlockHeader.number + 1u})",
            ),
          ),
        )
      } else {
        SafeFuture.completedFuture(Ok(true))
      }
    }

  val TimestampValidator =
    BlockValidator { block, _, prevBlockHeader, _ ->
      if (block.beaconBlockHeader.timestamp <= prevBlockHeader.timestamp) {
        SafeFuture.completedFuture(
          Err(
            BlockValidator.BlockValidationError(
              "Block timestamp (${block.beaconBlockHeader.timestamp})" +
                " is not greater than previous block timestamp (${prevBlockHeader.timestamp})",
            ),
          ),
        )
      } else {
        SafeFuture.completedFuture(Ok(true))
      }
    }

  val ProposerValidator =
    BlockValidator { block, proposerForBlock, _, _ ->
      if (block.beaconBlockHeader.proposer != proposerForBlock) {
        SafeFuture.completedFuture(
          Err(
            BlockValidator.BlockValidationError(
              "Proposer (${block.beaconBlockHeader.proposer}) " +
                "is not expected proposer ($proposerForBlock)",
            ),
          ),
        )
      } else {
        SafeFuture.completedFuture(Ok(true))
      }
    }

  val ParentRootValidator =
    BlockValidator { block, _, prevBlockHeader, _ ->
      if (!block.beaconBlockHeader.parentRoot.contentEquals(prevBlockHeader.hash)) {
        SafeFuture.completedFuture(
          Err(
            BlockValidator.BlockValidationError(
              "Parent root (${block.beaconBlockHeader.parentRoot.encodeHex()}) " +
                "does not match previous block root (${prevBlockHeader.hash.encodeHex()})",
            ),
          ),
        )
      } else {
        SafeFuture.completedFuture(Ok(true))
      }
    }

  val BodyRootValidator =
    BlockValidator { block, _, _, _ ->
      val beaconBodyRoot = HashUtil.bodyRoot(block.beaconBlockBody)
      if (!block.beaconBlockHeader.bodyRoot.contentEquals(beaconBodyRoot)) {
        SafeFuture.completedFuture(
          Err(
            BlockValidator.BlockValidationError(
              "Body root in header (${block.beaconBlockHeader.bodyRoot.encodeHex()}) " +
                "does not match body root (${beaconBodyRoot.encodeHex()})",
            ),
          ),
        )
      } else {
        SafeFuture.completedFuture(Ok(true))
      }
    }
}

class PrevBlockSealValidator(
  private val sealVerifier: SealVerifier,
) : BlockValidator {
  override fun validateBlock(
    newBlock: BeaconBlock,
    proposerForNewBlock: Validator,
    prevBlockHeader: BeaconBlockHeader,
    validatorsForPrevBlock: Set<Validator>,
  ): SafeFuture<Result<Boolean, BlockValidator.BlockValidationError>> {
    val committers = mutableSetOf<Validator>()
    for (seal in newBlock.beaconBlockBody.prevCommitSeals) {
      when (val sealVerificationResult = sealVerifier.verifySealAndExtractValidator(seal, prevBlockHeader)) {
        is Ok -> committers.add(sealVerificationResult.value)
        is Err -> return SafeFuture.completedFuture(
          Err(
            BlockValidator.BlockValidationError(
              "Previous block seal verification failed. Reason: ${sealVerificationResult.error.message}",
            ),
          ),
        )
      }
    }
    val quorumCount = BftHelpers.calculateRequiredValidatorQuorum(validatorsForPrevBlock.size)
    if (committers.size < quorumCount) {
      return SafeFuture.completedFuture(
        Err(
          BlockValidator.BlockValidationError(
            "Quorum threshold not met. " +
              "Committers: ${committers.size}, " +
              "Validators: ${validatorsForPrevBlock.size}, " +
              "QuorumCount: $quorumCount",
          ),
        ),
      )
    }
    return SafeFuture.completedFuture(Ok(true))
  }
}

class ExecutionPayloadValidator(
  private val executionLayerClient: ExecutionLayerClient,
) : BlockValidator {
  override fun validateBlock(
    newBlock: BeaconBlock,
    proposerForNewBlock: Validator,
    prevBlockHeader: BeaconBlockHeader,
    validatorsForPrevBlock: Set<Validator>,
  ): SafeFuture<Result<Boolean, BlockValidator.BlockValidationError>> =
    executionLayerClient.newPayload(newBlock.beaconBlockBody.executionPayload).thenApply { newPayloadResponse ->
      if (newPayloadResponse.isSuccess && newPayloadResponse.payload.hasValidExecutionPayload()) {
        Ok(true)
      } else {
        Err(
          BlockValidator.BlockValidationError(
            "Execution payload validation failed: ${newPayloadResponse.errorMessage}",
          ),
        )
      }
    }
}
