/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.executionlayer.client

import java.util.Optional
import maru.config.consensus.ElFork
import maru.core.ExecutionPayload
import maru.extensions.captureTimeSafeFuture
import maru.mappers.Mappers.toDomainExecutionPayload
import maru.mappers.Mappers.toExecutionPayloadV1
import net.consensys.linea.metrics.MetricsFacade
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceStateV1
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceUpdatedResult
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadAttributesV1
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadStatusV1
import tech.pegasys.teku.ethereum.executionclient.schema.Response
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JClient
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.bytes.Bytes8

// https://github.com/ethereum/execution-apis/blob/main/src/engine/paris.md
class ParisWeb3JJsonRpcExecutionLayerEngineApiClient(
  web3jClient: Web3JClient,
  metricsFacade: MetricsFacade,
) : BaseWeb3JJsonRpcExecutionLayerEngineApiClient(web3jClient = web3jClient, metricsFacade = metricsFacade) {
  override fun getFork(): ElFork = ElFork.Paris

  override fun getPayload(payloadId: Bytes8): SafeFuture<Response<ExecutionPayload>> =
    createRequestTimer<ExecutionPayload>(method = "getPayload").captureTimeSafeFuture(
      web3jEngineClient.getPayloadV1(payloadId).thenApply {
        when {
          it.payload != null ->
            Response.fromPayloadReceivedAsJson(it.payload.toDomainExecutionPayload())

          it.errorMessage != null ->
            Response.fromErrorMessage(it.errorMessage)

          else ->
            throw IllegalStateException("Failed to get payload!")
        }
      },
    )

  override fun newPayload(executionPayload: ExecutionPayload): SafeFuture<Response<PayloadStatusV1>> =
    createRequestTimer<PayloadStatusV1>(method = "newPayload").captureTimeSafeFuture(
      web3jEngineClient
        .newPayloadV1(executionPayload.toExecutionPayloadV1())
        .thenApply {
          if (it.payload != null) {
            Response.fromPayloadReceivedAsJson(it.payload)
          } else {
            Response.fromErrorMessage(it.errorMessage)
          }
        },
    )

  override fun forkChoiceUpdate(
    forkChoiceState: ForkChoiceStateV1,
    payloadAttributes: PayloadAttributesV1?,
  ): SafeFuture<Response<ForkChoiceUpdatedResult>> =
    createRequestTimer<ForkChoiceUpdatedResult>(
      method = "forkChoiceUpdate",
    ).captureTimeSafeFuture(
      web3jEngineClient.forkChoiceUpdatedV1(forkChoiceState, Optional.ofNullable(payloadAttributes)),
    )
}
