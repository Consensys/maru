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
import maru.core.ExecutionPayload
import maru.mappers.Mappers.toDomainExecutionPayload
import maru.mappers.Mappers.toExecutionPayloadV3
import maru.metrics.MaruMetricsCategory
import net.consensys.linea.metrics.MetricsFacade
import net.consensys.linea.metrics.Tag
import net.consensys.linea.metrics.TimerCapture
import org.apache.tuweni.bytes.Bytes32
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceStateV1
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceUpdatedResult
import tech.pegasys.teku.ethereum.executionclient.schema.GetPayloadV4Response
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadAttributesV1
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadAttributesV3
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadStatusV1
import tech.pegasys.teku.ethereum.executionclient.schema.Response
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JExecutionEngineClient
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.bytes.Bytes8

class PragueWeb3JJsonRpcExecutionLayerEngineApiClient(
  private val web3jEngineClient: Web3JExecutionEngineClient,
  private val metricsFacade: MetricsFacade,
) : ExecutionLayerEngineApiClient {
  private fun <T> createRequestTimer(method: String): TimerCapture<Response<T>> {
    val endpoint = ""
    return metricsFacade.createDynamicTagTimer<Response<T>>(
      category = MaruMetricsCategory.ENGINE_API,
      name = "request.latency",
      description = "Execution Engine API request latency",
      tags = listOf(Tag("fork", "prague"), Tag("endpoint", endpoint), Tag("method", method)),
      dynamicTagValueExtractor = {
        when {
          it.payload != null -> "success"
          else -> "failure"
        }
      },
      dynamicTagKey = "status",
      dynamicTagValueExtractorOnError = { "ERROR" },
    )
  }

  override fun getPayload(payloadId: Bytes8): SafeFuture<Response<ExecutionPayload>> =
    createRequestTimer<GetPayloadV4Response>("getPayload")
      .captureTime(web3jEngineClient.getPayloadV4(payloadId))
      .thenApply {
        when {
          it.payload != null -> {
            Response.fromPayloadReceivedAsJson(it.payload.executionPayload.toDomainExecutionPayload())
          }
          it.errorMessage != null -> {
            Response.fromErrorMessage(it.errorMessage)
          }
          else ->
            throw IllegalStateException("Failed to get payload!")
        }
      }

  override fun newPayload(executionPayload: ExecutionPayload): SafeFuture<Response<PayloadStatusV1>> =
    createRequestTimer<PayloadStatusV1>("newPayload")
      .captureTime(
        web3jEngineClient
          .newPayloadV4(executionPayload.toExecutionPayloadV3(), emptyList(), Bytes32.ZERO, emptyList()),
      ).thenApply {
        if (it.payload != null) {
          Response.fromPayloadReceivedAsJson(it.payload)
        } else {
          Response.fromErrorMessage(it.errorMessage)
        }
      }

  override fun forkChoiceUpdate(
    forkChoiceState: ForkChoiceStateV1,
    payloadAttributes: PayloadAttributesV1?,
  ): SafeFuture<Response<ForkChoiceUpdatedResult>> =
    createRequestTimer<ForkChoiceUpdatedResult>("forkChoiceUpdate")
      .captureTime(
        web3jEngineClient.forkChoiceUpdatedV3(forkChoiceState, Optional.ofNullable(payloadAttributes?.toV3())),
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
