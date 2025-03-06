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
package maru.executionlayer.client

import java.util.Optional
import org.apache.tuweni.bytes.Bytes32
import tech.pegasys.teku.ethereum.executionclient.schema.ExecutionPayloadV1
import tech.pegasys.teku.ethereum.executionclient.schema.ExecutionPayloadV3
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceStateV1
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceUpdatedResult
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadAttributesV1
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadAttributesV3
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadStatusV1
import tech.pegasys.teku.ethereum.executionclient.schema.Response
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JExecutionEngineClient
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.bytes.Bytes8
import tech.pegasys.teku.infrastructure.unsigned.UInt64

class PragueWeb3jJsonRpcExecutionLayerClient(
  private val web3jEngineClient: Web3JExecutionEngineClient,
) : ExecutionLayerClient {
  override fun getPayload(payloadId: Bytes8): SafeFuture<Response<ExecutionPayloadV1>> =
    web3jEngineClient.getPayloadV4(payloadId).thenApply {
      Response.fromPayloadReceivedAsJson(it.payload.executionPayload)
    }

  override fun newPayload(executionPayload: ExecutionPayloadV1): SafeFuture<Response<PayloadStatusV1>> =
    web3jEngineClient.newPayloadV4(executionPayload.toV3(), emptyList(), Bytes32.ZERO, emptyList()).thenApply {
      Response.fromPayloadReceivedAsJson(it.payload)
    }

  override fun forkChoiceUpdate(
    forkChoiceState: ForkChoiceStateV1,
    payloadAttributes: PayloadAttributesV1?,
  ): SafeFuture<Response<ForkChoiceUpdatedResult>> =
    web3jEngineClient.forkChoiceUpdatedV3(forkChoiceState, Optional.ofNullable(payloadAttributes?.toV3()))

  private fun ExecutionPayloadV1.toV3(): ExecutionPayloadV3 =
    ExecutionPayloadV3(
      /* parentHash = */ this.parentHash,
      /* feeRecipient = */ this.feeRecipient,
      /* stateRoot = */ this.stateRoot,
      /* receiptsRoot = */ this.receiptsRoot,
      /* logsBloom = */ this.logsBloom,
      /* prevRandao = */ this.prevRandao,
      /* blockNumber = */ this.blockNumber,
      /* gasLimit = */ this.gasLimit,
      /* gasUsed = */ this.gasUsed,
      /* timestamp = */ this.timestamp,
      /* extraData = */ this.extraData,
      /* baseFeePerGas = */ this.baseFeePerGas,
      /* blockHash = */ this.blockHash,
      /* transactions = */ this.transactions,
      /* withdrawals = */ emptyList(),
      /* blobGasUsed = */ UInt64.ZERO,
      /* excessBlobGas = */ UInt64.ZERO,
    )

  private fun PayloadAttributesV1.toV3(): PayloadAttributesV3 =
    PayloadAttributesV3(
      this.timestamp,
      this.prevRandao,
      this.suggestedFeeRecipient,
      emptyList(),
      Bytes32.ZERO,
    )
}
