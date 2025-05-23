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
import com.github.michaelbull.result.mapError
import maru.consensus.qbft.ProposerSelector
import maru.consensus.qbft.toConsensusRoundIdentifier
import maru.consensus.state.StateTransition
import maru.consensus.validation.BlockValidator.BlockValidationError
import maru.consensus.validation.BlockValidator.Companion.error
import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import maru.core.EMPTY_HASH
import maru.core.HashUtil
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import maru.extensions.encodeHex
import maru.serialization.rlp.bodyRoot
import maru.serialization.rlp.stateRoot
import tech.pegasys.teku.infrastructure.async.SafeFuture

interface BlockValidator {
  data class BlockValidationError(
    val message: String,
  )

  companion object {
    fun ok(): Result<Unit, BlockValidationError> = Ok(Unit)

    fun error(message: String): Result<Unit, BlockValidationError> = Err(BlockValidationError(message))

    fun require(
      condition: Boolean,
      errorMessageProvider: () -> String,
    ): Result<Unit, BlockValidationError> =
      if (condition) {
        ok()
      } else {
        error(errorMessageProvider())
      }
  }

  fun validateBlock(block: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>>
}

class CompositeBlockValidator(
  private val blockValidators: List<BlockValidator>,
) : BlockValidator {
  override fun validateBlock(block: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> {
    val validationResultFutures =
      blockValidators
        .map { it.validateBlock(block) }
        .stream()
    return SafeFuture.collectAll(validationResultFutures).thenApply { validationResults ->
      val errors = validationResults.mapNotNull { it.component2() }
      BlockValidator.require(errors.isEmpty()) {
        errors.joinToString { it.message }
      }
    }
  }
}

class BlockNumberValidator(
  private val parentBlockHeader: BeaconBlockHeader,
) : BlockValidator {
  override fun validateBlock(block: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> {
    val parentBlockNumber = parentBlockHeader.number
    return SafeFuture.completedFuture(
      BlockValidator.require(block.beaconBlockHeader.number == parentBlockNumber + 1u) {
        "Beacon block number is not the next block number blockNumber=${block.beaconBlockHeader.number} " +
          "parentBlockNumber=$parentBlockNumber"
      },
    )
  }
}

class TimestampValidator(
  private val parentBlockHeader: BeaconBlockHeader,
) : BlockValidator {
  override fun validateBlock(block: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> {
    val parentBlockTimeStamp = parentBlockHeader.timestamp
    return SafeFuture.completedFuture(
      BlockValidator.require(
        block.beaconBlockHeader.timestamp > parentBlockTimeStamp,
      ) {
        "Block timestamp is not greater than previous block timestamp " +
          "blockTimestamp=${block.beaconBlockHeader.timestamp} " +
          "parentBlockTimestamp=$parentBlockTimeStamp"
      },
    )
  }
}

class ProposerValidator(
  private val proposerSelector: ProposerSelector,
  private val beaconChain: BeaconChain,
) : BlockValidator {
  override fun validateBlock(block: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> {
    val parentState = beaconChain.getBeaconState(block.beaconBlockHeader.parentRoot)
    return if (parentState == null) {
      SafeFuture.completedFuture(
        error("Beacon state not found for block parentHash=${block.beaconBlockHeader.parentRoot.encodeHex()}"),
      )
    } else {
      proposerSelector
        .getProposerForBlock(
          parentState,
          block.beaconBlockHeader
            .toConsensusRoundIdentifier(),
        ).thenApply { proposerForNewBlock ->
          BlockValidator.require(block.beaconBlockHeader.proposer == proposerForNewBlock) {
            "Proposer is not expected proposer proposer=${block.beaconBlockHeader.proposer} " +
              "expectedProposer=$proposerForNewBlock"
          }
        }
    }
  }
}

class ParentRootValidator(
  private val parentBlockHeader: BeaconBlockHeader,
) : BlockValidator {
  override fun validateBlock(block: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> =
    SafeFuture.completedFuture(
      BlockValidator.require(
        block.beaconBlockHeader.parentRoot.contentEquals(
          parentBlockHeader
            .hash,
        ),
      ) {
        "Parent beacon root does not match parent block root parentRoot=${
          block.beaconBlockHeader.parentRoot
            .encodeHex()
        } " +
          "expectedParentRoot=${parentBlockHeader.hash.encodeHex()}"
      },
    )
}

class StateRootValidator(
  private val stateTransition: StateTransition,
) : BlockValidator {
  override fun validateBlock(block: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> =
    stateTransition
      .processBlock(block)
      .thenApply { postState ->
        val stateRootHeader =
          postState.latestBeaconBlockHeader.copy(
            stateRoot = EMPTY_HASH,
          )
        val expectedStateRoot = HashUtil.stateRoot(postState.copy(latestBeaconBlockHeader = stateRootHeader))
        BlockValidator.require(block.beaconBlockHeader.stateRoot.contentEquals(expectedStateRoot)) {
          "State root in header does not match state root stateRoot=${block.beaconBlockHeader.stateRoot.encodeHex()} " +
            "expectedStateRoot=${expectedStateRoot.encodeHex()}"
        }
      }
}

class BodyRootValidator : BlockValidator {
  override fun validateBlock(block: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> {
    val beaconBodyRoot = HashUtil.bodyRoot(block.beaconBlockBody)
    return SafeFuture.completedFuture(
      BlockValidator.require(
        block.beaconBlockHeader.bodyRoot.contentEquals
          (beaconBodyRoot),
      ) {
        "Body root in header does not match body root bodyRoot=${block.beaconBlockHeader.bodyRoot.encodeHex()} " +
          "expectedBodyRoot=${beaconBodyRoot.encodeHex()}"
      },
    )
  }
}

class PrevCommitSealValidator(
  private val beaconChain: BeaconChain,
  private val sealsVerifier: SealsVerifier,
  private val config: Config,
) : BlockValidator {
  data class Config(
    val prevBlockOffset: UInt,
  )

  override fun validateBlock(block: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> {
    val prevBlockNumber = block.beaconBlockHeader.number - config.prevBlockOffset

    val prevBlock =
      beaconChain.getSealedBeaconBlock(prevBlockNumber)?.beaconBlock ?: return SafeFuture.completedFuture(
        error("Previous block not found, previousBlockNumber=$prevBlockNumber"),
      )

    return sealsVerifier
      .verifySeals(block.beaconBlockBody.prevCommitSeals, prevBlock.beaconBlockHeader)
      .thenApply {
        it.mapError { errorMessage ->
          BlockValidationError("Previous block seal verification failed. Reason: $errorMessage")
        }
      }.exceptionally { ex ->
        error(ex.message!!)
      }
  }
}

class ExecutionPayloadValidator(
  private val executionLayerManager: ExecutionLayerManager,
) : BlockValidator {
  override fun validateBlock(block: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> =
    executionLayerManager.newPayload(block.beaconBlockBody.executionPayload).thenApply { newPayloadStatus ->
      BlockValidator.require(
        newPayloadStatus.status.isValid(),
      ) {
        "Execution payload validation failed: ${newPayloadStatus.validationError}," +
          " status=${newPayloadStatus.status}," +
          " latestValidHash=${newPayloadStatus.latestValidHash}"
      }
    }
}

object EmptyBlockValidator : BlockValidator {
  override fun validateBlock(block: BeaconBlock): SafeFuture<Result<Unit, BlockValidationError>> =
    SafeFuture.completedFuture(
      BlockValidator.require(
        block.beaconBlockBody.executionPayload.transactions
          .isNotEmpty(),
      ) {
        "Block number=${block.beaconBlockHeader.number} " +
          "executionPayloadBlockNumber=${block.beaconBlockBody.executionPayload.blockNumber} " +
          "hash=${block.beaconBlockHeader.hash.encodeHex()} is empty!"
      },
    )
}
