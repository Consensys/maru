package maru.executionlayer.manager

import java.math.BigInteger
import maru.core.executionlayer.client.BlockNumberAndHash
import maru.core.executionlayer.client.ExecutionLayerClient
import maru.core.executionlayer.manager.BlockBuildingResult
import maru.core.executionlayer.manager.ExecutionLayerManager
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.bytes.Bytes32
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceStateV1
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceUpdatedResult
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadAttributesV3
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadStatusV1
import tech.pegasys.teku.ethereum.executionclient.schema.Response
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.bytes.Bytes20
import tech.pegasys.teku.infrastructure.bytes.Bytes8
import tech.pegasys.teku.infrastructure.unsigned.UInt64

class JsonRpcExecutionLayerManager private constructor(
  private val executionLayerClient: ExecutionLayerClient,
  private val newBlockTimestampProvider: () -> ULong,
  private val feeRecipientProvider: () -> ByteArray,
  private val currentBlockNumberAndHash: BlockNumberAndHash
) :
  ExecutionLayerManager {
  companion object {
    fun create(
      executionLayerClient: ExecutionLayerClient,
      newBlockTimestampProvider: () -> ULong,
      feeRecipientProvider: () -> ByteArray
    ): SafeFuture<JsonRpcExecutionLayerManager> {
      return executionLayerClient.getLatestBlockMetadata().thenApply {
        val currentBlockNumberAndHash = BlockNumberAndHash(it.blockNumber, it.blockHash)
        JsonRpcExecutionLayerManager(
          executionLayerClient = executionLayerClient,
          newBlockTimestampProvider = newBlockTimestampProvider,
          feeRecipientProvider = feeRecipientProvider,
          currentBlockNumberAndHash = currentBlockNumberAndHash
        )
      }
    }
  }

  private class ElHeightMetadata(
    var nextBlockNumberAndHash: BlockNumberAndHash?,
    var currentBlockNumberAndHash: BlockNumberAndHash
  ) {
    @Synchronized
    fun updateNext(blockNumberAndHash: BlockNumberAndHash) {
      nextBlockNumberAndHash = blockNumberAndHash
    }

    @Synchronized
    fun promoteBlockNumberAndHash() {
      currentBlockNumberAndHash = nextBlockNumberAndHash!!
      nextBlockNumberAndHash = null
    }
  }

  private var payloadId: Bytes8? = null
  private var latestBlockCache: ElHeightMetadata = ElHeightMetadata(
    nextBlockNumberAndHash = null,
    currentBlockNumberAndHash = currentBlockNumberAndHash
  )

  override fun setHeadAndStartBlockBuilding(
    headHash: ByteArray,
    safeHash: ByteArray,
    finalizedHash: ByteArray
  ): SafeFuture<Response<ForkChoiceUpdatedResult>> {
    val payloadAttributes = PayloadAttributesV3(
      UInt64.valueOf(BigInteger(newBlockTimestampProvider().toString())),
      Bytes32.ZERO,
      Bytes20(Bytes.wrap(feeRecipientProvider())),
      emptyList(),
      Bytes32.ZERO,
    )
    return executionLayerClient
      .forkChoiceUpdate(
        ForkChoiceStateV1(
          Bytes32.wrap(headHash),
          Bytes32.wrap(safeHash),
          Bytes32.wrap(finalizedHash)
        ),
        payloadAttributes,
      ).thenApply {
        if (it.isSuccess) {
          payloadId = it.payload.asInternalExecutionPayload().payloadId.get()
          latestBlockCache.promoteBlockNumberAndHash()
        }
        it
      }
  }

  override fun finishBlockBuilding(): SafeFuture<BlockBuildingResult> {
    if (payloadId == null) {
      return SafeFuture.failedFuture(
        IllegalStateException(
          "finishBlockBuilding is called before setHeadAndStartBlockBuilding was completed"
        )
      )
    }
    return executionLayerClient.getPayload(payloadId!!)
      .thenCompose {
        payloadResponse ->
        if (payloadResponse.isSuccess) {
          val executionPayload = payloadResponse.payload.executionPayload
          latestBlockCache.updateNext(
            BlockNumberAndHash(
              executionPayload.blockNumber.longValue()
                .toULong(), executionPayload.blockHash
            )
          )
          executionLayerClient.newPayload(executionPayload).thenApply {
            payloadStatus ->
            if (payloadStatus.isSuccess) {
              BlockBuildingResult(executionPayload, payloadStatus.payload)
            } else {
              throw IllegalStateException("engine_newPayload request failed! Cause: " + payloadStatus.errorMessage)
            }
          }
        } else {
          SafeFuture.failedFuture(IllegalStateException("engine_getPayload request failed! Cause: " + payloadResponse.errorMessage))
        }
      }
  }

  override fun latestBlockHeight(): ULong {
    return latestBlockCache.currentBlockNumberAndHash.blockNumber
  }
}
