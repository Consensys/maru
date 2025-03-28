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
import maru.consensus.ProposerSelector
import maru.consensus.ValidatorProvider
import maru.consensus.state.StateTransition
import maru.consensus.toConsensusRoundIdentifier
import maru.consensus.validation.BlockValidator.BlockValidationError
import maru.consensus.validation.BlockValidator.Companion.error
import maru.consensus.validation.BlockValidator.Companion.ok
import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.HashUtil
import maru.core.Validator
import maru.database.BeaconChain
import maru.executionlayer.client.ExecutionLayerClient
import maru.executionlayer.extensions.hasValidExecutionPayload
import maru.serialization.rlp.bodyRoot
import maru.serialization.rlp.stateRoot
import org.hyperledger.besu.consensus.common.bft.BftHelpers
import tech.pegasys.teku.infrastructure.async.SafeFuture

fun interface BlockValidator {
  data class BlockValidationError(
    val message: String,
  )

  companion object {
    fun ok(): Result<Unit, BlockValidationError> = Ok(Unit)

    fun error(message: String): Result<Unit, BlockValidationError> = Err(BlockValidationError(message))
  }

  fun validateBlock(newBlock: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>>
}

class CompositeBlockValidator(
  private val blockValidators: List<BlockValidator>,
) : BlockValidator {
  override fun validateBlock(newBlock: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> {
    val validationResultFutures =
      blockValidators
        .map { it.validateBlock(newBlock) }
        .stream()
    return SafeFuture.collectAll(validationResultFutures).thenApply { validationResults ->
      val errors = validationResults.mapNotNull { it.component2() }
      if (errors.isEmpty()) {
        ok()
      } else {
        error(errors.joinToString { it.message })
      }
    }
  }
}

class StateRootValidator(
  private val stateTransition: StateTransition,
) : BlockValidator {
  override fun validateBlock(newBlock: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> =
    stateTransition.processBlock(newBlock).thenApply { stateTransitionResult ->
      when (stateTransitionResult) {
        is Ok<BeaconState> -> {
          val postState = stateTransitionResult.value
          val stateRootHeader =
            postState.latestBeaconBlockHeader.copy(
              stateRoot = BeaconBlockHeader.EMPTY_STATE_ROOT,
            )
          val expectedStateRoot = HashUtil.stateRoot(postState.copy(latestBeaconBlockHeader = stateRootHeader))
          if (!newBlock.beaconBlockHeader.stateRoot.contentEquals(expectedStateRoot)) {
            error(
              "State root in header does not match state root " +
                "stateRoot=${newBlock.beaconBlockHeader.stateRoot.encodeHex()} " +
                "expectedStateRoot=${expectedStateRoot.encodeHex()}",
            )
          } else {
            ok()
          }
        }
        is Err<StateTransition.StateTransitionError> -> error("State transition failed: ${stateTransitionResult.error}")
      }
    }
}

class BlockNumberValidator(
  private val beaconChain: BeaconChain,
) : BlockValidator {
  override fun validateBlock(newBlock: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> {
    val currentState = beaconChain.getLatestBeaconState()
    val parentBlockNumber = currentState.latestBeaconBlockHeader.number
    return if (newBlock.beaconBlockHeader.number != parentBlockNumber + 1u) {
      SafeFuture.completedFuture(
        error(
          "Block number is not the next block number " +
            "blockNumber=${newBlock.beaconBlockHeader.number} " +
            "nextBlockNumber=${parentBlockNumber + 1u}",
        ),
      )
    } else {
      SafeFuture.completedFuture(ok())
    }
  }
}

class TimestampValidator(
  private val beaconChain: BeaconChain,
) : BlockValidator {
  override fun validateBlock(newBlock: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> {
    val parentBlockHeader = beaconChain.getLatestBeaconState().latestBeaconBlockHeader
    return if (newBlock.beaconBlockHeader.timestamp <= parentBlockHeader.timestamp) {
      SafeFuture.completedFuture(
        error(
          "Block timestamp is not greater than previous block timestamp " +
            "blockTimestamp=${newBlock.beaconBlockHeader.timestamp} " +
            "parentBlockTimestamp=${parentBlockHeader.timestamp}",
        ),
      )
    } else {
      SafeFuture.completedFuture(ok())
    }
  }
}

class ProposerValidator(
  private val proposerSelector: ProposerSelector,
) : BlockValidator {
  override fun validateBlock(newBlock: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> =
    proposerSelector
      .getProposerForBlock(newBlock.beaconBlockHeader.toConsensusRoundIdentifier())
      .thenApply { proposerForNewBlock ->
        if (newBlock.beaconBlockHeader.proposer != proposerForNewBlock) {
          Err(
            BlockValidationError(
              "Proposer is not expected proposer " +
                "proposer=${newBlock.beaconBlockHeader.proposer} " +
                "expectedProposer=$proposerForNewBlock",
            ),
          )
        } else {
          ok()
        }
      }
}

class ParentRootValidator(
  private val beaconChain: BeaconChain,
) : BlockValidator {
  override fun validateBlock(newBlock: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> {
    val parentBlockHeader = beaconChain.getLatestBeaconState().latestBeaconBlockHeader
    return if (!newBlock.beaconBlockHeader.parentRoot.contentEquals(parentBlockHeader.hash)) {
      SafeFuture.completedFuture(
        error(
          "Parent root does not match parent block root " +
            "parentRoot=${newBlock.beaconBlockHeader.parentRoot.encodeHex()} " +
            "expectedParentRoot=${parentBlockHeader.hash.encodeHex()}",
        ),
      )
    } else {
      SafeFuture.completedFuture(ok())
    }
  }
}

class BodyRootValidator : BlockValidator {
  override fun validateBlock(newBlock: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> {
    val beaconBodyRoot = HashUtil.bodyRoot(newBlock.beaconBlockBody)
    return if (!newBlock.beaconBlockHeader.bodyRoot.contentEquals(beaconBodyRoot)) {
      SafeFuture.completedFuture(
        error(
          "Body root in header does not match body root " +
            "bodyRoot=${newBlock.beaconBlockHeader.bodyRoot.encodeHex()} " +
            "expectedBodyRoot=${beaconBodyRoot.encodeHex()}",
        ),
      )
    } else {
      SafeFuture.completedFuture(ok())
    }
  }
}

class PrevCommitSealValidator(
  private val sealVerifier: SealVerifier,
  private val beaconChain: BeaconChain,
  private val validatorProvider: ValidatorProvider,
  private val config: Config,
) : BlockValidator {
  data class Config(
    val prevBlockOffset: UInt,
  )

  private fun verifySeals(
    newBlock: BeaconBlock,
    prevBlock: BeaconBlock,
    validatorsForPrevBlock: Set<Validator>,
  ): Result<Unit, BlockValidationError> {
    val committers = mutableSetOf<Validator>()
    for (seal in newBlock.beaconBlockBody.prevCommitSeals) {
      when (val sealVerificationResult = sealVerifier.extractValidator(seal, prevBlock.beaconBlockHeader)) {
        is Ok -> {
          val sealValidator = sealVerificationResult.value
          if (sealValidator !in validatorsForPrevBlock) {
            return error(
              "Seal validator is not in the parent block's validator set " +
                "seal=$seal " +
                "sealValidator=$sealValidator " +
                "validatorsForParentBlock=$validatorsForPrevBlock",
            )
          }
          committers.add(sealVerificationResult.value)
        }

        is Err ->
          return error("Previous block seal verification failed. Reason: ${sealVerificationResult.error.message}")
      }
    }
    val quorumCount = BftHelpers.calculateRequiredValidatorQuorum(validatorsForPrevBlock.size)
    if (committers.size < quorumCount) {
      return error(
        "Quorum threshold not met. " +
          "committers=${committers.size} " +
          "validators=${validatorsForPrevBlock.size} " +
          "quorumCount=$quorumCount",
      )
    }
    return ok()
  }

  override fun validateBlock(newBlock: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> {
    val prevBlockNumber =
      beaconChain.getLatestBeaconState().latestBeaconBlockHeader.number - (config.prevBlockOffset - 1u)

    val prevBlock =
      beaconChain.getSealedBeaconBlock(prevBlockNumber) ?: return SafeFuture.completedFuture(
        error("Previous block not found, previousBlockNumber=$prevBlockNumber"),
      )

    return validatorProvider
      .getValidatorsForBlock(prevBlock.beaconBlock.beaconBlockHeader.number)
      .thenApply { validatorsForPrevBlock -> verifySeals(newBlock, prevBlock.beaconBlock, validatorsForPrevBlock) }
  }
}

class ExecutionPayloadValidator(
  private val executionLayerClient: ExecutionLayerClient,
) : BlockValidator {
  override fun validateBlock(newBlock: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> =
    executionLayerClient.newPayload(newBlock.beaconBlockBody.executionPayload).thenApply { newPayloadResponse ->
      if (newPayloadResponse.isSuccess && newPayloadResponse.payload.hasValidExecutionPayload()) {
        ok()
      } else {
        error(
          "Execution payload validation failed: ${newPayloadResponse.errorMessage}",
        )
      }
    }
}
