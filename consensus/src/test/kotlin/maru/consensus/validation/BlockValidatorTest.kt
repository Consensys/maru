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
import maru.consensus.state.StateTransitionImpl
import maru.consensus.toConsensusRoundIdentifier
import maru.consensus.validation.BlockValidator.Companion.error
import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.HashUtil
import maru.core.Seal
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.database.InMemoryBeaconChain
import maru.executionlayer.client.ExecutionLayerClient
import maru.serialization.rlp.bodyRoot
import maru.serialization.rlp.stateRoot
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.consensus.common.bft.BftHelpers
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadStatusV1
import tech.pegasys.teku.ethereum.executionclient.schema.Response
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.spec.executionlayer.ExecutionPayloadStatus

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
      latestBeaconBlockRoot = validCurrBlockHeader.bodyRoot,
      validators = validators.toSet(),
    )

  private val validNewBlockBody = DataGenerators.randomBeaconBlockBody(numSeals = validators.size)
  private val validNewBlockStateRootHeader =
    DataGenerators.randomBeaconBlockHeader(newBlockNumber).copy(
      proposer = validators[1],
      parentRoot = validCurrBlockHeader.hash,
      timestamp = validCurrBlockHeader.timestamp + 1u,
      bodyRoot = HashUtil.bodyRoot(validNewBlockBody),
      stateRoot = BeaconBlockHeader.EMPTY_STATE_ROOT,
    )
  private val validNewStateRoot =
    HashUtil.stateRoot(
      BeaconState(
        latestBeaconBlockHeader = validNewBlockStateRootHeader,
        latestBeaconBlockRoot = validNewBlockStateRootHeader.bodyRoot,
        validators = validators.toSet(),
      ),
    )
  private val validNewBlockHeader = validNewBlockStateRootHeader.copy(stateRoot = validNewStateRoot)
  private val validNewBlock = BeaconBlock(validNewBlockHeader, validNewBlockBody)

  private val beaconChain = InMemoryBeaconChain(currBeaconState)

  private val proposerSelector =
    ProposerSelector { consensusRoundIdentifier ->
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

  init {
    beaconChain.newUpdater().putSealedBeaconBlock(SealedBeaconBlock(validCurrBlock, emptyList())).commit()
  }

  @Test
  fun `test valid block`() {
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
      ProposerValidator(proposerSelector = proposerSelector).also {
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

    val sealVerifier =
      object : SealVerifier {
        override fun extractValidator(
          seal: Seal,
          beaconBlockHeader: BeaconBlockHeader,
        ): Result<Validator, SealVerifier.SealValidationError> {
          assertThat(beaconBlockHeader).isEqualTo(validCurrBlockHeader)
          return when (seal) {
            validNewBlockBody.prevCommitSeals[0] -> return Ok(validators[0])
            validNewBlockBody.prevCommitSeals[1] -> return Ok(validators[1])
            validNewBlockBody.prevCommitSeals[2] -> return Ok(validators[2])
            else -> Err(SealVerifier.SealValidationError("Invalid seal"))
          }
        }
      }

    val prevCommitSealValidator =
      PrevCommitSealValidator(
        sealVerifier = sealVerifier,
        beaconChain = beaconChain,
        validatorProvider = validatorProvider,
        config = PrevCommitSealValidator.Config(prevBlockOffset = 1u),
      ).also {
        assertThat(it.validateBlock(block = validNewBlock).get()).isEqualTo(BlockValidator.ok())
      }

    val executionLayerClient =
      mock<ExecutionLayerClient> {
        on { newPayload(any()) }.thenReturn(
          SafeFuture.completedFuture(
            Response.fromPayloadReceivedAsSsz(
              PayloadStatusV1(ExecutionPayloadStatus.VALID, null, null),
            ),
          ),
        )
      }
    val executionPayloadValidator =
      ExecutionPayloadValidator(executionLayerClient).also {
        assertThat(it.validateBlock(block = validNewBlock).get()).isEqualTo(BlockValidator.ok())
      }

    val blockValidators =
      listOf(
        stateRootValidator,
        blockNumberValidator,
        timestampValidator,
        proposerValidator,
        parentRootValidator,
        bodyRootValidator,
        prevCommitSealValidator,
        executionPayloadValidator,
      )
    CompositeBlockValidator(blockValidators).also {
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
        "Block number is not the next block number " +
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
        "Block number is not the next block number " +
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
    val proposerValidator = ProposerValidator(proposerSelector = proposerSelector)
    val result =
      proposerValidator
        .validateBlock(
          block = invalidBlock,
        ).get()
    val expectedResult =
      error(
        "Proposer is not expected proposer " +
          "proposer=${invalidBlockHeader.proposer} " +
          "expectedProposer=${validNewBlockHeader.proposer}",
      )
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
        "Parent root does not match parent block root " +
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
  fun `test not enough commit seals`() {
    assertThat(BftHelpers.calculateRequiredValidatorQuorum(3)).isEqualTo(2)

    val blockBodyWithEnoughSeals =
      validNewBlockBody.copy(
        prevCommitSeals =
          listOf(
            validNewBlockBody.prevCommitSeals[0],
            validNewBlockBody.prevCommitSeals[1],
          ),
      )

    val blockBodyWithLessSeals =
      validNewBlockBody.copy(
        prevCommitSeals = listOf(validNewBlockBody.prevCommitSeals[0]),
      )

    val sealVerifier =
      object : SealVerifier {
        override fun extractValidator(
          seal: Seal,
          beaconBlockHeader: BeaconBlockHeader,
        ): Result<Validator, SealVerifier.SealValidationError> =
          when (seal) {
            validNewBlockBody.prevCommitSeals[0] -> Ok(validators[0])
            validNewBlockBody.prevCommitSeals[1] -> Ok(validators[1])
            else -> Err(SealVerifier.SealValidationError("Invalid seal"))
          }
      }
    val prevCommitSealValidator =
      PrevCommitSealValidator(
        sealVerifier = sealVerifier,
        beaconChain = beaconChain,
        validatorProvider = validatorProvider,
        config = PrevCommitSealValidator.Config(prevBlockOffset = 1u),
      )
    val validResult =
      prevCommitSealValidator
        .validateBlock(
          block = validNewBlock.copy(beaconBlockBody = blockBodyWithEnoughSeals),
        ).get()
    assertThat(validResult).isEqualTo(BlockValidator.ok())

    val inValidResult =
      prevCommitSealValidator
        .validateBlock(
          block = validNewBlock.copy(beaconBlockBody = blockBodyWithLessSeals),
        ).get()
    val expectedResult =
      error(
        "Quorum threshold not met. " +
          "committers=1 " +
          "validators=3 " +
          "quorumCount=2",
      )
    assertThat(inValidResult).isEqualTo(expectedResult)
  }

  @Test
  fun `test commit seals not from validator`() {
    val sealVerifier =
      object : SealVerifier {
        override fun extractValidator(
          seal: Seal,
          beaconBlockHeader: BeaconBlockHeader,
        ): Result<Validator, SealVerifier.SealValidationError> = Ok(nonValidatorNode)
      }

    val prevCommitSealValidator =
      PrevCommitSealValidator(
        sealVerifier = sealVerifier,
        beaconChain = beaconChain,
        validatorProvider = validatorProvider,
        config = PrevCommitSealValidator.Config(prevBlockOffset = 1u),
      )

    val result =
      prevCommitSealValidator
        .validateBlock(
          block = validNewBlock,
        ).get()
    val expectedResult =
      error(
        "Seal validator is not in the parent block's validator set " +
          "seal=${validNewBlockBody.prevCommitSeals[0]} " +
          "sealValidator=$nonValidatorNode " +
          "validatorsForParentBlock=$validators",
      )
    assertThat(result).isEqualTo(expectedResult)
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

    val prevCommitSealValidator =
      PrevCommitSealValidator(
        sealVerifier = sealVerifier,
        beaconChain = beaconChain,
        validatorProvider = validatorProvider,
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
      mock<ExecutionLayerClient> {
        on { newPayload(any()) }.thenReturn(
          SafeFuture.completedFuture(Response.fromErrorMessage("Invalid execution payload")),
        )
      }
    val result =
      ExecutionPayloadValidator(executionLayerClient = invalidExecutionClient)
        .validateBlock(
          block = validNewBlock.copy(beaconBlockBody = blockBody),
        ).get()
    val expectedResult =
      error(
        "Execution payload validation failed: Invalid execution payload",
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
        "Block number=${validNewBlock.beaconBlockHeader.number} " +
          "hash=${validNewBlock.beaconBlockHeader.hash.encodeHex()} is empty!",
      )
    assertThat(result).isEqualTo(expectedResult)
  }
}
