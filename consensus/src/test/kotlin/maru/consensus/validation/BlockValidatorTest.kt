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
import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import maru.core.ExecutionPayload
import maru.core.HashUtil
import maru.core.Seal
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.executionlayer.client.ExecutionLayerClient
import maru.serialization.rlp.bodyRoot
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
  private val currBlockNumber = 9uL
  private val newBlockNumber = 10uL
  private val validCurrBlockBody = DataGenerators.randomBeaconBlockBody(numSeals = validators.size)
  private val validCurrBlockHeader =
    DataGenerators.randomBeaconBlockHeader(currBlockNumber).copy(
      proposer = validators[0],
      bodyRoot = HashUtil.bodyRoot(validCurrBlockBody),
    )
  private val validCurrBlock = BeaconBlock(validCurrBlockHeader, validCurrBlockBody)
  private val validNewBlockBody = DataGenerators.randomBeaconBlockBody(numSeals = validators.size)
  private val validNewBlockHeader =
    DataGenerators.randomBeaconBlockHeader(newBlockNumber).copy(
      proposer = validators[1],
      parentRoot = validCurrBlockHeader.hash,
      timestamp = validCurrBlockHeader.timestamp + 1u,
      bodyRoot = HashUtil.bodyRoot(validNewBlockBody),
    )
  private val validNewBlock = BeaconBlock(validNewBlockHeader, validNewBlockBody)

  private val proposerSelector =
    object : ProposerSelector {
      override fun getProposerForBlock(header: BeaconBlockHeader): SafeFuture<Validator> =
        when (header.number) {
          newBlockNumber -> SafeFuture.completedFuture(Validator(validNewBlockHeader.proposer.address))
          currBlockNumber -> SafeFuture.completedFuture(Validator(validCurrBlockHeader.proposer.address))
          else -> SafeFuture.completedFuture(validators[2])
        }
    }

  private val sealVerifier =
    object : SealVerifier {
      override fun verifySealAndExtractValidator(
        seal: Seal,
        beaconBlockHeader: BeaconBlockHeader,
      ): Result<Validator, SealVerifier.SealValidationError> {
        val validatorIndex =
          when (beaconBlockHeader.number + 1u) {
            currBlockNumber -> validCurrBlock.beaconBlockBody.prevCommitSeals.indexOf(seal)
            newBlockNumber -> validNewBlock.beaconBlockBody.prevCommitSeals.indexOf(seal)
            else -> -1
          }
        return if (validatorIndex != -1) {
          Ok(validators[validatorIndex])
        } else {
          Err(SealVerifier.SealValidationError("Invalid seal"))
        }
      }
    }

  private val executionLayerClient =
    mock<ExecutionLayerClient> {
      on { newPayload(any()) }.thenAnswer {
        val executionPayload = it.getArgument<ExecutionPayload>(0)
        if (executionPayload == validNewBlockBody.executionPayload ||
          executionPayload == validCurrBlockBody.executionPayload
        ) {
          val mockPayload = PayloadStatusV1(ExecutionPayloadStatus.VALID, null, null)
          SafeFuture.completedFuture(Response(mockPayload))
        } else {
          SafeFuture.completedFuture(Response.withErrorMessage<PayloadStatusV1>("Invalid execution payload"))
        }
      }
    }

  @Test
  fun `test valid block`() {
    val compositeBlockValidator =
      CompositeBlockValidator(
        blockValidators =
          listOf(
            BlockValidators.BlockNumberValidator,
            BlockValidators.TimestampValidator,
            BlockValidators.ProposerValidator,
            BlockValidators.ParentRootValidator,
            BlockValidators.BodyRootValidator,
            PrevBlockSealValidator(sealVerifier = sealVerifier),
            ExecutionPayloadValidator(executionLayerClient),
          ),
      )
    val result =
      compositeBlockValidator
        .validateBlock(
          newBlock = validNewBlock,
          proposerForNewBlock = validNewBlock.beaconBlockHeader.proposer,
          prevBlockHeader = validCurrBlockHeader,
          validatorsForPrevBlock = validators.toSet(),
        ).get()
    assertThat(result is Ok).isTrue()
  }

  @Test
  fun `test invalid previous block number`() {
    val prevBlockHeader = validCurrBlockHeader.copy(number = 8u)
    val result =
      BlockValidators.BlockNumberValidator
        .validateBlock(
          newBlock = validNewBlock,
          proposerForNewBlock = validNewBlock.beaconBlockHeader.proposer,
          prevBlockHeader = prevBlockHeader,
          validatorsForPrevBlock = validators.toSet(),
        ).get()
    assertThat(result is Err).isTrue()
    assertThat(result.component2()).isNotNull()
    assertThat(result.component2()?.message).isEqualTo(
      "Block number ($newBlockNumber) is not the next block number (${prevBlockHeader.number + 1u})",
    )
  }

  @Test
  fun `test invalid block timestamp, equal to previous block timestamp`() {
    val blockHeader = validNewBlockHeader.copy(timestamp = validCurrBlockHeader.timestamp)
    val result =
      BlockValidators.TimestampValidator
        .validateBlock(
          newBlock = validNewBlock.copy(beaconBlockHeader = blockHeader),
          prevBlockHeader = validCurrBlockHeader,
          proposerForNewBlock = validNewBlock.beaconBlockHeader.proposer,
          validatorsForPrevBlock = validators.toSet(),
        ).get()
    assertThat(result is Err).isTrue()
    assertThat(result.component2()).isNotNull()
    assertThat(result.component2()?.message)
      .isEqualTo(
        "Block timestamp (${blockHeader.timestamp}) " +
          "is not greater than previous block timestamp (${validCurrBlockHeader.timestamp})",
      )
  }

  @Test
  fun `test invalid block timestamp, less than previous block timestamp`() {
    val blockHeader = validNewBlockHeader.copy(timestamp = validCurrBlockHeader.timestamp - 1u)
    val result =
      BlockValidators.TimestampValidator
        .validateBlock(
          newBlock = validNewBlock.copy(beaconBlockHeader = blockHeader),
          prevBlockHeader = validCurrBlockHeader,
          proposerForNewBlock = validNewBlock.beaconBlockHeader.proposer,
          validatorsForPrevBlock = validators.toSet(),
        ).get()
    assertThat(result is Err).isTrue()
    assertThat(result.component2()).isNotNull()
    assertThat(result.component2()?.message)
      .isEqualTo(
        "Block timestamp (${blockHeader.timestamp}) " +
          "is not greater than previous block timestamp (${validCurrBlockHeader.timestamp})",
      )
  }

  @Test
  fun `test invalid block proposer`() {
    val result =
      BlockValidators.ProposerValidator
        .validateBlock(
          newBlock = validNewBlock,
          prevBlockHeader = validCurrBlockHeader,
          proposerForNewBlock = validators.last(),
          validatorsForPrevBlock = validators.toSet(),
        ).get()
    assertThat(result is Err).isTrue()
    assertThat(result.component2()).isNotNull()
    assertThat(result.component2()?.message).contains("is not expected proposer")
  }

  @Test
  fun `test invalid parent root`() {
    val blockHeader = validNewBlockHeader.copy(parentRoot = validCurrBlockHeader.hash.reversedArray())
    val result =
      BlockValidators.ParentRootValidator
        .validateBlock(
          newBlock = validNewBlock.copy(beaconBlockHeader = blockHeader),
          prevBlockHeader = validCurrBlockHeader,
          proposerForNewBlock = validNewBlockHeader.proposer,
          validatorsForPrevBlock = validators.toSet(),
        ).get()
    assertThat(result is Err).isTrue()
    assertThat(result.component2()).isNotNull()
    assertThat(result.component2()?.message)
      .isEqualTo(
        "Parent root (${blockHeader.parentRoot.encodeHex()}) " +
          "does not match previous block root (${validCurrBlockHeader.hash.encodeHex()})",
      )
  }

  @Test
  fun `test invalid body root`() {
    val blockHeader = validNewBlockHeader.copy(bodyRoot = validNewBlockHeader.bodyRoot.reversedArray())
    val result =
      BlockValidators.BodyRootValidator
        .validateBlock(
          newBlock = validNewBlock.copy(beaconBlockHeader = blockHeader),
          prevBlockHeader = validCurrBlockHeader,
          proposerForNewBlock = validCurrBlockHeader.proposer,
          validatorsForPrevBlock = validators.toSet(),
        ).get()
    assertThat(result is Err).isTrue()
    assertThat(result.component2()).isNotNull()
    assertThat(result.component2()?.message).contains(
      "Body root in header (${blockHeader.bodyRoot.encodeHex()}) " +
        "does not match body root (${validNewBlockHeader.bodyRoot.encodeHex()})",
    )
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
    val validResult =
      PrevBlockSealValidator(sealVerifier = sealVerifier)
        .validateBlock(
          newBlock = validNewBlock.copy(beaconBlockBody = blockBodyWithEnoughSeals),
          prevBlockHeader = validCurrBlockHeader,
          proposerForNewBlock = validNewBlockHeader.proposer,
          validatorsForPrevBlock = validators.toSet(),
        ).get()
    assertThat(validResult is Ok).isTrue()

    val blockBodyWithLessSeals =
      validNewBlockBody.copy(
        prevCommitSeals = listOf(validNewBlockBody.prevCommitSeals[0]),
      )
    val inValidResult =
      PrevBlockSealValidator(sealVerifier = sealVerifier)
        .validateBlock(
          newBlock = validNewBlock.copy(beaconBlockBody = blockBodyWithLessSeals),
          prevBlockHeader = validCurrBlockHeader,
          proposerForNewBlock = validNewBlockHeader.proposer,
          validatorsForPrevBlock = validators.toSet(),
        ).get()
    assertThat(inValidResult is Err).isTrue()
    assertThat(inValidResult.component2()?.message)
      .isEqualTo("Quorum threshold not met. Committers: 1, Validators: 3, QuorumCount: 2")
  }

  @Test
  fun `test invalid commit seals`() {
    val blockBody =
      validNewBlockBody.copy(
        prevCommitSeals =
          validNewBlockBody.prevCommitSeals.map {
            Seal(it.signature.reversedArray())
          },
      )
    val result =
      PrevBlockSealValidator(sealVerifier = sealVerifier)
        .validateBlock(
          newBlock = validNewBlock.copy(beaconBlockBody = blockBody),
          prevBlockHeader = validCurrBlockHeader,
          proposerForNewBlock = validNewBlockHeader.proposer,
          validatorsForPrevBlock = validators.toSet(),
        ).get()
    assertThat(result is Err).isTrue()
    assertThat(result.component2()?.message)
      .isEqualTo("Previous block seal verification failed. Reason: Invalid seal")
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
    val result =
      ExecutionPayloadValidator(executionLayerClient = executionLayerClient)
        .validateBlock(
          newBlock = validNewBlock.copy(beaconBlockBody = blockBody),
          prevBlockHeader = validCurrBlockHeader,
          proposerForNewBlock = validNewBlockHeader.proposer,
          validatorsForPrevBlock = validators.toSet(),
        ).get()
    assertThat(result is Err).isTrue()
    assertThat(result.component2()?.message)
      .isEqualTo("Execution payload validation failed: Invalid execution payload")
  }
}
