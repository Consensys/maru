package maru.core.executionlayer.manager

import tech.pegasys.teku.ethereum.executionclient.schema.ExecutionPayloadV3
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceUpdatedResult
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadStatusV1
import tech.pegasys.teku.ethereum.executionclient.schema.Response
import tech.pegasys.teku.infrastructure.async.SafeFuture

data class BlockBuildingResult(val executionPayload: ExecutionPayloadV3, val payloadStatus: PayloadStatusV1)

interface ExecutionLayerManager {
  fun setHeadAndStartBlockBuilding(headHash: ByteArray, safeHash: ByteArray, finalizedHash: ByteArray):
    SafeFuture<Response<ForkChoiceUpdatedResult>>
  fun finishBlockBuilding(): SafeFuture<BlockBuildingResult>
  fun latestBlockHeight(): ULong
}
