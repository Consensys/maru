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
import com.github.michaelbull.result.getError
import maru.consensus.ValidatorProvider
import maru.consensus.qbft.ProposerSelector
import maru.consensus.qbft.toConsensusRoundIdentifier
import maru.consensus.state.StateTransitionImpl
import maru.consensus.validation.BlockValidator.Companion.error
import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.EMPTY_HASH
import maru.core.HashUtil
import maru.core.Seal
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.database.InMemoryBeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import maru.executionlayer.manager.ExecutionPayloadStatus
import maru.executionlayer.manager.PayloadStatus
import maru.extensions.encodeHex
import maru.serialization.rlp.bodyRoot
import maru.serialization.rlp.stateRoot
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.consensus.common.bft.BftHelpers
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import tech.pegasys.teku.infrastructure.async.SafeFuture

class BlockValidatorTest {
  private val validators = (1..3).map { DataGenerators.randomValidator() }
  private val nonValidatorNode = DataGenerators.randomValidator()

  private val currBlockNumber = 9uL
  private val newBlockNumber = 10uL

  private val validCurrBlockBody = DataGenerators.randomBeaconBlockBody(numSeals = validators.size)
  private val validCurrBlockHeader =
    DataGenerators.randomBeaconBlockHeader(currBlockNumber).copy(
      proposer = validators[0],
      bodyRoot = HashUtil.bodyRoot(validCurrBlockBody),
    )
  private val validCurrBlock = BeaconBlock(validCurrBlockHeader, validCurrBlockBody)

  private val currBeaconState =
    BeaconState(
      latestBeaconBlockHeader = validCurrBlockHeader,
      validators = validators.toSet(),
    )

  private val validNewBlockBody = DataGenerators.randomBeaconBlockBody(numSeals = validators.size)
  private val validNewBlockStateRootHeader =
    DataGenerators.randomBeaconBlockHeader(newBlockNumber).copy(
      proposer = validators[1],
      parentRoot = validCurrBlockHeader.hash,
      timestamp = validCurrBlockHeader.timestamp + 1u,
      bodyRoot = HashUtil.bodyRoot(validNewBlockBody),
      stateRoot = EMPTY_HASH,
    )
  private val validNewStateRoot =
    HashUtil.stateRoot(
      BeaconState(
        latestBeaconBlockHeader = validNewBlockStateRootHeader,
        validators = validators.toSet(),
      ),
    )
  private val validNewBlockHeader = validNewBlockStateRootHeader.copy(stateRoot = validNewStateRoot)
  private val validNewBlock = BeaconBlock(validNewBlockHeader, validNewBlockBody)

  private val beaconChain =
    InMemoryBeaconChain(currBeaconState).also {
      it
        .newUpdater()
        .putSealedBeaconBlock(SealedBeaconBlock(validCurrBlock, emptySet()))
        .commit()
    }

  private val proposerSelector =
    ProposerSelector { beaconState, consensusRoundIdentifier ->
      when (consensusRoundIdentifier) {
        validNewBlockHeader.toConsensusRoundIdentifier() -> SafeFuture.completedFuture(validNewBlockHeader.proposer)
        else -> throw IllegalArgumentException("Unexpected consensus round identifier")
      }
    }

  private val validatorProvider =
    object : ValidatorProvider {
      override fun getValidatorsForBlock(blockNumber: ULong): SafeFuture<Set<Validator>> =
        SafeFuture.completedFuture(validators.toSet())
    }

  private val stateTransition = StateTransitionImpl(validatorProvider)

  @Test
  fun `test validator's validation on a valid block`() {
    val executionLayerEngineApiClient =
      mock<ExecutionLayerManager> {
        on { newPayload(any()) }.thenReturn(
          SafeFuture.completedFuture(
            DataGenerators.randomValidPayloadStatus(),
          ),
        )
      }

    val blockValidator =
      BeaconBlockValidatorFactoryImpl(
        beaconChain = beaconChain,
        proposerSelector = proposerSelector,
        stateTransition = stateTransition,
        executionLayerManager = executionLayerEngineApiClient,
      ).createValidatorForBlock(validNewBlock.beaconBlockHeader)
    blockValidator.also {
      assertThat(it.validateBlock(block = validNewBlock).get()).isEqualTo(BlockValidator.ok())
    }
  }

  @Test
  fun `test all the validations on a valid block`() {
    val stateRootValidator =
      StateRootValidator(stateTransition).also {
        assertThat(it.validateBlock(block = validNewBlock).get()).isEqualTo(BlockValidator.ok())
      }

    val blockNumberValidator =
      BlockNumberValidator(parentBlockHeader = validCurrBlockHeader).also {
        assertThat(it.validateBlock(block = validNewBlock).get()).isEqualTo(BlockValidator.ok())
      }

    val timestampValidator =
      TimestampValidator(parentBlockHeader = validCurrBlockHeader).also {
        assertThat(it.validateBlock(block = validNewBlock).get()).isEqualTo(BlockValidator.ok())
      }

    val proposerValidator =
      ProposerValidator(proposerSelector = proposerSelector, beaconChain = beaconChain).also {
        assertThat(it.validateBlock(block = validNewBlock).get()).isEqualTo(BlockValidator.ok())
      }

    val parentRootValidator =
      ParentRootValidator(parentBlockHeader = validCurrBlockHeader).also {
        assertThat(it.validateBlock(block = validNewBlock).get()).isEqualTo(BlockValidator.ok())
      }

    val bodyRootValidator =
      BodyRootValidator().also {
        assertThat(it.validateBlock(block = validNewBlock).get()).isEqualTo(BlockValidator.ok())
      }
    val executionLayerEngineApiClient =
      mock<ExecutionLayerManager> {
        on { newPayload(any()) }.thenReturn(
          SafeFuture.completedFuture(
            DataGenerators.randomValidPayloadStatus(),
          ),
        )
      }
    val executionPayloadValidator =
      ExecutionPayloadValidator(executionLayerEngineApiClient).also {
        assertThat(it.validateBlock(block = validNewBlock).get()).isEqualTo(BlockValidator.ok())
      }
    val sealVerifier =
      object : SealVerifier {
        override fun extractValidator(
          seal: Seal,
          beaconBlockHeader: BeaconBlockHeader,
        ): Result<Validator, SealVerifier.SealValidationError> {
          assertThat(beaconBlockHeader).isEqualTo(validCurrBlockHeader)
          return when (seal) {
            validNewBlockBody.prevCommitSeals.elementAt(0) -> return Ok(validators[0])
            validNewBlockBody.prevCommitSeals.elementAt(1) -> return Ok(validators[1])
            validNewBlockBody.prevCommitSeals.elementAt(2) -> return Ok(validators[2])
            else -> Err(SealVerifier.SealValidationError("Invalid seal"))
          }
        }
      }

    val sealsVerifier = QuorumOfSealsVerifier(validatorProvider, sealVerifier)
    val prevCommitSealValidator =
      PrevCommitSealValidator(
        beaconChain = beaconChain,
        sealsVerifier = sealsVerifier,
        config = PrevCommitSealValidator.Config(prevBlockOffset = 1u),
      ).also {
        assertThat(it.validateBlock(block = validNewBlock).get()).isEqualTo(BlockValidator.ok())
      }
    val blockValidator =
      CompositeBlockValidator(
        blockValidators =
          listOf(
            stateRootValidator,
            blockNumberValidator,
            timestampValidator,
            proposerValidator,
            parentRootValidator,
            bodyRootValidator,
            executionPayloadValidator,
            EmptyBlockValidator,
            prevCommitSealValidator,
          ),
      )

    blockValidator.also {
      assertThat(it.validateBlock(block = validNewBlock).get()).isEqualTo(BlockValidator.ok())
    }
  }

  @Test
  fun `test invalid state root`() {
    val invalidNewBlockHeader = validNewBlockHeader.copy(stateRoot = validNewStateRoot.reversedArray())
    val invalidNewBlock = validNewBlock.copy(beaconBlockHeader = invalidNewBlockHeader)
    val stateRootValidator = StateRootValidator(stateTransition)
    val result = stateRootValidator.validateBlock(block = invalidNewBlock).get()
    val expectedResult =
      error(
        "State root in header does not match state root " +
          "stateRoot=${invalidNewBlockHeader.stateRoot.encodeHex()} " +
          "expectedStateRoot=${validNewStateRoot.encodeHex()}",
      )
    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `test invalid previous block number, number lower`() {
    val blockNumberValidator = BlockNumberValidator(parentBlockHeader = validCurrBlockHeader)
    val invalidBlock =
      validNewBlock.copy(
        beaconBlockHeader = validNewBlockHeader.copy(number = validNewBlockHeader.number - 1u),
      )
    val result =
      blockNumberValidator
        .validateBlock(
          block = invalidBlock,
        ).get()
    val expectedResult =
      error(
        "Beacon block number is not the next block number " +
          "blockNumber=${invalidBlock.beaconBlockHeader.number} " +
          "parentBlockNumber=${validCurrBlock.beaconBlockHeader.number}",
      )
    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `test invalid previous block number, number higher`() {
    val blockNumberValidator = BlockNumberValidator(parentBlockHeader = validCurrBlockHeader)
    val invalidBlock =
      validNewBlock.copy(
        beaconBlockHeader = validNewBlockHeader.copy(number = validNewBlockHeader.number + 1u),
      )
    val result =
      blockNumberValidator
        .validateBlock(
          block = invalidBlock,
        ).get()
    val expectedResult =
      error(
        "Beacon block number is not the next block number " +
          "blockNumber=${invalidBlock.beaconBlockHeader.number} " +
          "parentBlockNumber=${validCurrBlock.beaconBlockHeader.number}",
      )
    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `test invalid block timestamp, equal to previous block timestamp`() {
    val timestampValidator = TimestampValidator(parentBlockHeader = validCurrBlockHeader)
    val invalidBlockHeader = validNewBlockHeader.copy(timestamp = validCurrBlockHeader.timestamp)
    val invalidBlock = validNewBlock.copy(beaconBlockHeader = invalidBlockHeader)
    val result =
      timestampValidator
        .validateBlock(
          block = invalidBlock,
        ).get()
    val expectedResult =
      error(
        "Block timestamp is not greater than previous block timestamp " +
          "blockTimestamp=${invalidBlockHeader.timestamp} " +
          "parentBlockTimestamp=${validCurrBlockHeader.timestamp}",
      )
    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `test invalid block timestamp, less than previous block timestamp`() {
    val timestampValidator = TimestampValidator(parentBlockHeader = validCurrBlockHeader)
    val invalidBlockHeader = validNewBlockHeader.copy(timestamp = validCurrBlockHeader.timestamp - 1u)
    val invalidBlock = validNewBlock.copy(beaconBlockHeader = invalidBlockHeader)
    val result =
      timestampValidator
        .validateBlock(
          block = invalidBlock,
        ).get()
    val expectedResult =
      error(
        "Block timestamp is not greater than previous block timestamp " +
          "blockTimestamp=${invalidBlockHeader.timestamp} " +
          "parentBlockTimestamp=${validCurrBlockHeader.timestamp}",
      )

    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `test invalid block proposer`() {
    val invalidBlockHeader = validNewBlockHeader.copy(proposer = nonValidatorNode)
    val invalidBlock = validNewBlock.copy(beaconBlockHeader = invalidBlockHeader)
    val proposerValidator = ProposerValidator(proposerSelector = proposerSelector, beaconChain = beaconChain)
    val result = proposerValidator.validateBlock(invalidBlock).get()
    val expectedResult =
      error(
        "Proposer is not expected proposer " +
          "proposer=${invalidBlockHeader.proposer} " +
          "expectedProposer=${validNewBlockHeader.proposer}",
      )
    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `test missing parent block state`() {
    val nonExistentParentHash = EMPTY_HASH
    val invalidBlockHeader = validNewBlockHeader.copy(parentRoot = nonExistentParentHash)
    val invalidBlock = validNewBlock.copy(beaconBlockHeader = invalidBlockHeader)
    val proposerValidator = ProposerValidator(proposerSelector = proposerSelector, beaconChain = beaconChain)
    val result = proposerValidator.validateBlock(invalidBlock).get()
    val expectedResult =
      error("Beacon state not found for block parentHash=${nonExistentParentHash.encodeHex()}")
    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `test invalid parent root`() {
    val parentRootValidator = ParentRootValidator(parentBlockHeader = validCurrBlockHeader)
    val invalidBlockHeader = validNewBlockHeader.copy(parentRoot = validCurrBlockHeader.hash.reversedArray())
    val invalidBlock = validNewBlock.copy(beaconBlockHeader = invalidBlockHeader)
    val result =
      parentRootValidator
        .validateBlock(
          block = invalidBlock,
        ).get()
    val expectedResult =
      error(
        "Parent beacon root does not match parent block root " +
          "parentRoot=${invalidBlockHeader.parentRoot.encodeHex()} " +
          "expectedParentRoot=${validCurrBlockHeader.hash.encodeHex()}",
      )
    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `test invalid body root`() {
    val bodyRootValidator = BodyRootValidator()
    val invalidBlockHeader = validNewBlockHeader.copy(bodyRoot = validNewBlockHeader.bodyRoot.reversedArray())
    val invalidBlock = validNewBlock.copy(beaconBlockHeader = invalidBlockHeader)
    val result =
      bodyRootValidator
        .validateBlock(
          block = invalidBlock,
        ).get()
    val expectedResult =
      error(
        "Body root in header does not match body root bodyRoot=${invalidBlockHeader.bodyRoot.encodeHex()} " +
          "expectedBodyRoot=${validNewBlockHeader.bodyRoot.encodeHex()}",
      )
    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `seal verification error is propagated`() {
    assertThat(BftHelpers.calculateRequiredValidatorQuorum(3)).isEqualTo(2)

    val blockBodyWithEnoughSeals =
      validNewBlockBody.copy(
        prevCommitSeals =
          setOf(
            validNewBlockBody.prevCommitSeals.elementAt(0),
            validNewBlockBody.prevCommitSeals.elementAt(1),
          ),
      )
    val expectedError = "Seal verification error!"
    val sealsVerifier =
      object : SealsVerifier {
        override fun verifySeals(
          seals: Set<Seal>,
          beaconBlockHeader: BeaconBlockHeader,
        ): SafeFuture<Result<Unit, String>> = SafeFuture.completedFuture(Err(expectedError))
      }
    val prevCommitSealValidator =
      PrevCommitSealValidator(
        beaconChain = beaconChain,
        sealsVerifier = sealsVerifier,
        config = PrevCommitSealValidator.Config(prevBlockOffset = 1u),
      )
    val invalidResult =
      prevCommitSealValidator
        .validateBlock(
          block = validNewBlock.copy(beaconBlockBody = blockBodyWithEnoughSeals),
        ).get()
    assertThat(invalidResult).isInstanceOf(Err::class.java)
    assertThat(invalidResult.getError()!!.message).contains(expectedError)
  }

  @Test
  fun `seals verification failed future is propagated`() {
    val expectedMessage = "Test exception!"
    val sealsVerifier =
      object : SealsVerifier {
        override fun verifySeals(
          seals: Set<Seal>,
          beaconBlockHeader: BeaconBlockHeader,
        ): SafeFuture<Result<Unit, String>> = SafeFuture.failedFuture(Exception(expectedMessage))
      }
    val prevCommitSealValidator =
      PrevCommitSealValidator(
        beaconChain = beaconChain,
        sealsVerifier = sealsVerifier,
        config = PrevCommitSealValidator.Config(prevBlockOffset = 1u),
      )

    val result =
      prevCommitSealValidator
        .validateBlock(
          block = validNewBlock,
        ).get()
    assertThat(result).isInstanceOf(Err::class.java)
    assertThat(result.getError()!!.message).contains(expectedMessage)
  }

  @Test
  fun `test invalid commit seals`() {
    val sealVerifier =
      object : SealVerifier {
        override fun extractValidator(
          seal: Seal,
          beaconBlockHeader: BeaconBlockHeader,
        ): Result<Validator, SealVerifier.SealValidationError> = Err(SealVerifier.SealValidationError("Invalid seal"))
      }

    val sealsVerifier = QuorumOfSealsVerifier(validatorProvider, sealVerifier)
    val prevCommitSealValidator =
      PrevCommitSealValidator(
        beaconChain = beaconChain,
        sealsVerifier = sealsVerifier,
        config = PrevCommitSealValidator.Config(prevBlockOffset = 1u),
      )

    val result =
      prevCommitSealValidator
        .validateBlock(
          block = validNewBlock,
        ).get()
    val expectedResult = error("Previous block seal verification failed. Reason: Invalid seal")
    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `test invalid execution payload`() {
    val blockBody =
      validNewBlockBody.copy(
        executionPayload =
          validNewBlockBody.executionPayload.copy(
            timestamp = validNewBlockBody.executionPayload.timestamp + 1u,
          ),
      )
    val invalidExecutionClient =
      mock<ExecutionLayerManager> {
        on { newPayload(any()) }.thenReturn(
          SafeFuture.completedFuture(PayloadStatus(ExecutionPayloadStatus.INVALID, null, "Invalid execution payload")),
        )
      }
    val result =
      ExecutionPayloadValidator(executionLayerManager = invalidExecutionClient)
        .validateBlock(
          block = validNewBlock.copy(beaconBlockBody = blockBody),
        ).get()
    val expectedResult =
      error(
        "Execution payload validation failed: Invalid execution payload, status=INVALID, latestValidHash=null",
      )
    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `test nonempty block`() {
    val result =
      EmptyBlockValidator
        .validateBlock(
          block = validNewBlock,
        ).get()
    assertThat(result).isEqualTo(BlockValidator.ok())
  }

  @Test
  fun `test empty block`() {
    val blockBody =
      validNewBlockBody.copy(
        executionPayload =
          validNewBlockBody.executionPayload.copy(
            transactions = emptyList(),
          ),
      )
    val result =
      EmptyBlockValidator
        .validateBlock(
          block = validNewBlock.copy(beaconBlockBody = blockBody),
        ).get()
    val expectedResult =
      error(
        "Block is empty number=${validNewBlock.beaconBlockHeader.number} " +
          "executionPayloadBlockNumber=${validNewBlock.beaconBlockBody.executionPayload.blockNumber} " +
          "hash=${validNewBlock.beaconBlockHeader.hash.encodeHex()}",
      )
    assertThat(result).isEqualTo(expectedResult)
  }
}
