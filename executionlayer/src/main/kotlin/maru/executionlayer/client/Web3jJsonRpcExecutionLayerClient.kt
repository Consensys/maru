package maru.executionlayer.client

import java.util.Optional
import maru.core.executionlayer.client.BlockNumberAndHash
import maru.core.executionlayer.client.ExecutionLayerClient
import org.apache.tuweni.bytes.Bytes32
import org.web3j.protocol.core.DefaultBlockParameter
import tech.pegasys.teku.ethereum.executionclient.schema.ExecutionPayloadV3
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceStateV1
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceUpdatedResult
import tech.pegasys.teku.ethereum.executionclient.schema.GetPayloadV3Response
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadAttributesV3
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadStatusV1
import tech.pegasys.teku.ethereum.executionclient.schema.Response
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JExecutionEngineClient
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.bytes.Bytes8

class Web3jJsonRpcExecutionLayerClient(private val web3jEngineClient: Web3JExecutionEngineClient,
  private val web3jClient: Web3JClient
): ExecutionLayerClient {
  override fun getLatestBlockMetadata(): SafeFuture<BlockNumberAndHash> {
    return SafeFuture.of(web3jClient.eth1Web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf("latest"), false)
      .sendAsync()
      .minimalCompletionStage()).thenApply {
        BlockNumberAndHash(it.block.number.toLong().toULong(), Bytes32.fromHexString(it.block.hash))
    }
  }

  override fun getPayload(payloadId: Bytes8): SafeFuture<Response<GetPayloadV3Response>> {
    return web3jEngineClient.getPayloadV3(payloadId)
  }

  override fun newPayload(executionPayload: ExecutionPayloadV3): SafeFuture<Response<PayloadStatusV1>> {
    return web3jEngineClient.newPayloadV3(executionPayload, emptyList(), Bytes32.ZERO)
  }

  override fun forkChoiceUpdate(
    forkChoiceState: ForkChoiceStateV1,
    payloadAttributes: PayloadAttributesV3?
  ): SafeFuture<Response<ForkChoiceUpdatedResult>> {
    return web3jEngineClient.forkChoiceUpdatedV3(forkChoiceState, Optional.ofNullable(payloadAttributes))
  }
}
