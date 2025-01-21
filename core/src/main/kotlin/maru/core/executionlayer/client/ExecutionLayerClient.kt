package maru.core.executionlayer.client

import org.apache.tuweni.bytes.Bytes32
import tech.pegasys.teku.ethereum.executionclient.schema.ExecutionPayloadV3
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceStateV1
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceUpdatedResult
import tech.pegasys.teku.ethereum.executionclient.schema.GetPayloadV3Response
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadAttributesV3
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadStatusV1
import tech.pegasys.teku.ethereum.executionclient.schema.Response
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.bytes.Bytes8

data class BlockNumberAndHash(val blockNumber: ULong, val blockHash: Bytes32)

interface ExecutionLayerClient {
  fun getLatestBlockMetadata(): SafeFuture<BlockNumberAndHash>
  fun getPayload(payloadId: Bytes8): SafeFuture<Response<GetPayloadV3Response>>
  fun newPayload(executionPayload: ExecutionPayloadV3): SafeFuture<Response<PayloadStatusV1>>
  fun forkChoiceUpdate(forkChoiceState: ForkChoiceStateV1, payloadAttributes: PayloadAttributesV3?): SafeFuture<Response<ForkChoiceUpdatedResult>>
}
