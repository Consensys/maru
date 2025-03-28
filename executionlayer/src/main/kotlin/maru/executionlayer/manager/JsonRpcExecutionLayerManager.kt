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
package maru.executionlayer.manager

import kotlin.jvm.optionals.getOrNull
import maru.core.ExecutionPayload
import maru.executionlayer.client.ExecutionLayerClient
import maru.executionlayer.client.MetadataProvider
import maru.executionlayer.extensions.toPayloadAttributesV1
import org.apache.logging.log4j.LogManager
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.bytes.Bytes32
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceStateV1
import tech.pegasys.teku.ethereum.executionclient.schema.Response
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.bytes.Bytes8
import tech.pegasys.teku.spec.executionlayer.ExecutionPayloadStatus
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceUpdatedResult as TekuForkChoiceUpdatedResult

object NoopValidator : ExecutionPayloadValidator {
  override fun validate(executionPayload: ExecutionPayload): ExecutionPayloadValidator.ValidationResult =
    ExecutionPayloadValidator.ValidationResult.Valid(executionPayload)
}

class JsonRpcExecutionLayerManager private constructor(
  private val executionLayerClient: ExecutionLayerClient,
  currentBlockMetadata: BlockMetadata,
  private val payloadValidator: ExecutionPayloadValidator,
) : ExecutionLayerManager {
  private val log = LogManager.getLogger(this.javaClass)

  companion object {
    fun create(
      executionLayerClient: ExecutionLayerClient,
      metadataProvider: MetadataProvider,
      payloadValidator: ExecutionPayloadValidator,
    ): SafeFuture<JsonRpcExecutionLayerManager> =
      metadataProvider.getLatestBlockMetadata().thenApply {
        val currentBlockMetadata = BlockMetadata(it.blockNumber, it.blockHash, it.unixTimestampSeconds)
        JsonRpcExecutionLayerManager(
          executionLayerClient = executionLayerClient,
          currentBlockMetadata = currentBlockMetadata,
          payloadValidator = payloadValidator,
        )
      }
  }

  private class ElHeightMetadataCache(
    private var nextBlockMetadata: BlockMetadata,
    var currentBlockMetadata: BlockMetadata,
  ) {
    @Synchronized
    fun updateNext(blockNumberAndHash: BlockMetadata) {
      nextBlockMetadata = blockNumberAndHash
    }

    @Synchronized
    fun promoteBlockMetadata() {
      currentBlockMetadata = nextBlockMetadata
    }
  }

  private var payloadId: ByteArray? = null
  private var latestBlockCache: ElHeightMetadataCache =
    ElHeightMetadataCache(
      nextBlockMetadata = currentBlockMetadata,
      currentBlockMetadata = currentBlockMetadata,
    )

  override fun setHeadAndStartBlockBuilding(
    headHash: ByteArray,
    safeHash: ByteArray,
    finalizedHash: ByteArray,
    nextBlockTimestamp: Long,
    feeRecipient: ByteArray,
  ): SafeFuture<ForkChoiceUpdatedResult> {
    log.debug(
      "Trying to create a block number {} with timestamp {}",
      latestBlockCache.currentBlockMetadata.blockNumber + 1u,
      nextBlockTimestamp,
    )
    val payloadAttributes =
      PayloadAttributes(
        timestamp = nextBlockTimestamp,
        suggestedFeeRecipient = feeRecipient,
      )
    log.debug("Starting block building with payload attributes {}", payloadAttributes)
    return forkChoiceUpdate(headHash, safeHash, finalizedHash, payloadAttributes).thenPeek {
      log.debug("Setting payload Id, latest block metadata {}", latestBlockCache.currentBlockMetadata)
      payloadId = it.payloadId
    }
  }

  private fun mapForkChoiceUpdatedResultToDomain(
    forkChoiceUpdatedResult: Response<TekuForkChoiceUpdatedResult>,
  ): ForkChoiceUpdatedResult {
    val payload = forkChoiceUpdatedResult.payload.asInternalExecutionPayload()
    val parsedPayloadId =
      payload.payloadId
        .getOrNull()
        ?.wrappedBytes
        ?.toArray()
    val payloadStatusV1 = payload.payloadStatus
    val domainPayloadStatus =
      PayloadStatus(
        payloadStatusV1.status.getOrNull()?.name,
        payloadStatusV1.latestValidHash.getOrNull()?.toArray(),
        payloadStatusV1.validationError.getOrNull(),
        payloadStatusV1.failureCause.getOrNull(),
      )
    return ForkChoiceUpdatedResult(domainPayloadStatus, parsedPayloadId)
  }

  override fun finishBlockBuilding(): SafeFuture<ExecutionPayload> {
    if (payloadId == null) {
      return SafeFuture.failedFuture(
        IllegalStateException(
          "finishBlockBuilding is called before setHeadAndStartBlockBuilding was completed",
        ),
      )
    }
    return executionLayerClient
      .getPayload(Bytes8(Bytes.wrap(payloadId!!)))
      .thenCompose { payloadResponse ->
        if (payloadResponse.isSuccess) {
          val executionPayload = payloadResponse.payload
          val validationResult = payloadValidator.validate(executionPayload)

          if (validationResult is ExecutionPayloadValidator.ValidationResult.Invalid) {
            throw RuntimeException(validationResult.reason)
          }
          importPayload(executionPayload).thenApply {
            log.debug("Unsetting payload Id, latest block metadata {}", latestBlockCache.currentBlockMetadata)

            payloadId = null // Not necessary, but it helps to reinforce the order of calls
            executionPayload
          }
        } else {
          SafeFuture.failedFuture(
            IllegalStateException("engine_getPayload request failed! Cause: " + payloadResponse.errorMessage),
          )
        }
      }
  }

  override fun latestBlockMetadata(): BlockMetadata = latestBlockCache.currentBlockMetadata

  override fun setHead(
    headHash: ByteArray,
    safeHash: ByteArray,
    finalizedHash: ByteArray,
  ): SafeFuture<ForkChoiceUpdatedResult> = forkChoiceUpdate(headHash, safeHash, finalizedHash, null)

  private fun forkChoiceUpdate(
    headHash: ByteArray,
    safeHash: ByteArray,
    finalizedHash: ByteArray,
    payloadAttributes: PayloadAttributes?,
  ): SafeFuture<ForkChoiceUpdatedResult> =
    executionLayerClient
      .forkChoiceUpdate(
        ForkChoiceStateV1(
          Bytes32.wrap(headHash),
          Bytes32.wrap(safeHash),
          Bytes32.wrap(finalizedHash),
        ),
        payloadAttributes?.toPayloadAttributesV1(),
      ).thenCompose { response ->
        log.debug("Forkchoice update response with payload attributes {}", response)
        if (response.isFailure) {
          // TODO: Temporary hack for protocol switches. Should go when QBFT fully works along with dummy consensus
          executionLayerClient
            .forkChoiceUpdate(
              ForkChoiceStateV1(
                Bytes32.wrap(headHash),
                Bytes32.wrap(safeHash),
                Bytes32.wrap(finalizedHash),
              ),
              null,
            )
        } else {
          SafeFuture.completedFuture(response)
        }
      }.thenApply { response ->
        log.debug("Forkchoice update response after a retry without payload attributes {}", response)
        if (response.isFailure) {
          throw IllegalStateException(
            "forkChoiceUpdate request failed! nextBlockTimestamp=${
              payloadAttributes?.timestamp
            } " + response.errorMessage,
          )
        } else {
          latestBlockCache.promoteBlockMetadata()
          mapForkChoiceUpdatedResultToDomain(response)
        }
      }

  override fun importPayload(executionPayload: ExecutionPayload): SafeFuture<Unit> =
    executionLayerClient.newPayload(executionPayload).thenApply { payloadStatusResponse ->
      if (payloadStatusResponse.isSuccess) {
        val payloadStatus = payloadStatusResponse.payload.asInternalExecutionPayload()
        if (payloadStatus.status.get() == ExecutionPayloadStatus.VALID) {
          latestBlockCache.updateNext(
            BlockMetadata(
              executionPayload.blockNumber,
              executionPayload.blockHash,
              executionPayload.timestamp.toLong(),
            ),
          )
        } else {
          throw IllegalStateException("engine_newPayload request failed! Cause: " + payloadStatus.validationError.get())
        }
      } else {
        throw IllegalStateException("engine_newPayload request failed! Cause: " + payloadStatusResponse.errorMessage)
      }
    }
}
