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
import maru.core.ExecutionPayload
import maru.core.HashUtil
import maru.core.Seal
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.database.Database
import maru.executionlayer.client.ExecutionLayerClient
import maru.serialization.rlp.bodyRoot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadStatusV1
import tech.pegasys.teku.ethereum.executionclient.schema.Response
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.spec.executionlayer.ExecutionPayloadStatus

class BlockValidatorImplTest {
  private val validPrevBlockBody = DataGenerators.randomBeaconBlockBody()
  private val validPrevBlockHeader =
    DataGenerators.randomBeaconBlockHeader(9u).copy(
      bodyRoot = HashUtil.bodyRoot(validPrevBlockBody),
    )
  private val validPrevBlock = BeaconBlock(validPrevBlockHeader, validPrevBlockBody)
  private val validBlockBody = DataGenerators.randomBeaconBlockBody()
  private val validBlockHeader =
    DataGenerators.randomBeaconBlockHeader(validPrevBlockHeader.number + 1u).copy(
      parentRoot = validPrevBlockHeader.hash,
      timestamp = validPrevBlockHeader.timestamp + 1u,
      bodyRoot = HashUtil.bodyRoot(validBlockBody),
    )
  private val validBlock = BeaconBlock(validBlockHeader, validBlockBody)

  private val validatorProvider =
    mock<ValidatorProvider> {
      on { getProposerForBlock(any()) }.thenReturn(validBlockHeader.proposer)
      on { getValidatorsForBlock(any()) }
        .thenReturn((0 until validBlockBody.prevCommitSeals.size).map { DataGenerators.randomValidator() }.toSet())
    }
  private val sealVerifier =
    mock<SealVerifier> {
      on { verifySeal(any(), any()) } doReturn (Result.failure(RuntimeException("Invalid seal")))
      on {
        verifySeal(
          argThat { seal: Seal ->
            validBlockBody.prevCommitSeals.contains(seal)
          },
          eq(validPrevBlockHeader),
        )
      } doReturn (Result.success(true))
    }

  private val executionLayerClient =
    mock<ExecutionLayerClient> {
      on { newPayload(any()) }.thenAnswer {
        val executionPayload = it.getArgument<ExecutionPayload>(0)
        if (executionPayload == validBlockBody.executionPayload) {
          val mockPayload = PayloadStatusV1(ExecutionPayloadStatus.VALID, null, null)
          SafeFuture.completedFuture(Response(mockPayload))
        } else {
          SafeFuture.completedFuture(Response.withErrorMessage<PayloadStatusV1>("Invalid execution payload"))
        }
      }
    }

  private val database =
    mock<Database> {
      on { getSealedBeaconBlock(any()) }.thenAnswer {
        val beaconBlockRoot = it.getArgument<ByteArray>(0)
        if (beaconBlockRoot.contentEquals(validPrevBlockHeader.hash)) {
          SealedBeaconBlock(validPrevBlock, validBlockBody.prevCommitSeals)
        } else {
          null
        }
      }
    }

  private val blockValidator =
    BlockValidatorImpl(
      validatorProvider = validatorProvider,
      sealVerifier = sealVerifier,
      executionLayerClient = executionLayerClient,
      database = database,
    )

  @Test
  fun `test valid block`() {
    val result = blockValidator.validateBlock(validBlock, prevBlockHeader = validPrevBlockHeader).get()
    assertThat(result).isEqualTo(Result.success(true))
  }

  @Test
  fun `test invalid previous block`() {
    val block = DataGenerators.randomBeaconBlock(10u)
    val result = blockValidator.validateBlock(block).get()
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull())
      .isInstanceOf(BlockValidationException::class.java)
      .hasMessage("Invalid block: Previous block not found")
  }

  @Test
  fun `test invalid previous block number`() {
    val prevBlockHeader = validPrevBlockHeader.copy(number = 8u)
    val result = blockValidator.validateBlock(validBlock, prevBlockHeader = prevBlockHeader).get()
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull())
      .isInstanceOf(BlockValidationException::class.java)
      .hasMessage("Invalid block: Block number is not the next block number")
  }

  @Test
  fun `test invalid block timestamp`() {
    val blockHeader = validBlockHeader.copy(timestamp = validPrevBlockHeader.timestamp)
    val result =
      blockValidator
        .validateBlock(
          validBlock.copy(beaconBlockHeader = blockHeader),
          prevBlockHeader = validPrevBlockHeader,
        ).get()
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull())
      .isInstanceOf(BlockValidationException::class.java)
      .hasMessage("Invalid block: Block timestamp is not greater than previous block timestamp")
  }

  @Test
  fun `test invalid block proposer`() {
    val blockHeader = validBlockHeader.copy(proposer = Validator(validBlockHeader.proposer.address.reversedArray()))
    val result =
      blockValidator
        .validateBlock(
          validBlock.copy(beaconBlockHeader = blockHeader),
          prevBlockHeader = validPrevBlockHeader,
        ).get()
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull())
      .isInstanceOf(BlockValidationException::class.java)
      .hasMessage("Invalid block: Proposer is not expected proposer")
  }

  @Test
  fun `test invalid parent root`() {
    val blockHeader = validBlockHeader.copy(parentRoot = validPrevBlockHeader.hash.reversedArray())
    val result =
      blockValidator
        .validateBlock(
          validBlock.copy(beaconBlockHeader = blockHeader),
          prevBlockHeader = validPrevBlockHeader,
        ).get()
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull())
      .isInstanceOf(BlockValidationException::class.java)
      .hasMessage("Invalid block: Parent root does not match")
  }

  @Test
  fun `test invalid body root`() {
    val blockHeader = validBlockHeader.copy(bodyRoot = validBlockHeader.bodyRoot.reversedArray())
    val result =
      blockValidator
        .validateBlock(
          validBlock.copy(beaconBlockHeader = blockHeader),
          prevBlockHeader = validPrevBlockHeader,
        ).get()
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull())
      .isInstanceOf(BlockValidationException::class.java)
      .hasMessage("Invalid block: Body root does not match")
  }

  @Test
  fun `test not enough commit seals`() {
    val blockBody =
      validBlockBody.copy(
        prevCommitSeals = listOf(validPrevBlock.beaconBlockBody.prevCommitSeals.first()),
      )
    val blockHeader = validBlockHeader.copy(bodyRoot = HashUtil.bodyRoot(blockBody))
    val result =
      blockValidator
        .validateBlock(
          validBlock.copy(beaconBlockBody = blockBody, beaconBlockHeader = blockHeader),
          prevBlockHeader = validPrevBlockHeader,
        ).get()
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull())
      .isInstanceOf(BlockValidationException::class.java)
      .hasMessage("Invalid block: Not enough commit seals for previous block")
  }

  @Test
  fun `test invalid commit seals`() {
    val blockBody =
      validBlockBody.copy(
        prevCommitSeals =
          validBlockBody.prevCommitSeals.map {
            Seal(it.signature.reversedArray())
          },
      )
    val blockHeader = validBlockHeader.copy(bodyRoot = HashUtil.bodyRoot(blockBody))
    val result =
      blockValidator
        .validateBlock(
          validBlock.copy(beaconBlockBody = blockBody, beaconBlockHeader = blockHeader),
          prevBlockHeader = validPrevBlockHeader,
        ).get()
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull())
      .isInstanceOf(BlockValidationException::class.java)
      .hasMessage("Invalid block: Previous block seal verification failed, cause: Invalid seal")
  }

  @Test
  fun `test invalid execution payload`() {
    val blockBody =
      validBlockBody.copy(
        executionPayload =
          validBlockBody.executionPayload.copy(
            timestamp = validBlockBody.executionPayload.timestamp + 1u,
          ),
      )
    val blockHeader = validBlockHeader.copy(bodyRoot = HashUtil.bodyRoot(blockBody))
    val result =
      blockValidator
        .validateBlock(
          validBlock.copy(beaconBlockBody = blockBody, beaconBlockHeader = blockHeader),
          prevBlockHeader = validPrevBlockHeader,
        ).get()
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull())
      .isInstanceOf(BlockValidationException::class.java)
      .hasMessage("Invalid block: Execution payload validation failed: Invalid execution payload")
  }
}
