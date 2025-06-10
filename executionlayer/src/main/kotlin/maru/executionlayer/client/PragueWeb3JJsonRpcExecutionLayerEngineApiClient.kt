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
import maru.core.ExecutionPayload
import maru.extensions.captureTimeSafeFuture
import maru.mappers.Mappers.toDomainExecutionPayload
import maru.mappers.Mappers.toExecutionPayloadV3
import net.consensys.linea.metrics.CounterFactory
import net.consensys.linea.metrics.Tag
import net.consensys.linea.metrics.TimerFactory
import org.apache.tuweni.bytes.Bytes32
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceStateV1
import tech.pegasys.teku.ethereum.executionclient.schema.ForkChoiceUpdatedResult
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadAttributesV1
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadAttributesV3
import tech.pegasys.teku.ethereum.executionclient.schema.PayloadStatusV1
import tech.pegasys.teku.ethereum.executionclient.schema.Response
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JExecutionEngineClient
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.bytes.Bytes8

class PragueWeb3JJsonRpcExecutionLayerEngineApiClient(
  private val web3jEngineClient: Web3JExecutionEngineClient,
  private val timerFactory: TimerFactory,
  private val counterFactory: CounterFactory,
) : ExecutionLayerEngineApiClient {
  override fun getPayload(payloadId: Bytes8): SafeFuture<Response<ExecutionPayload>> =
    timerFactory
      .create(listOf(Tag("method", "getPayload")))
      .captureTimeSafeFuture(web3jEngineClient.getPayloadV4(payloadId))
      .thenApply {
        when {
          it.payload != null -> {
            counterFactory
              .create(
                listOf(
                  Tag("method", "getPayload"),
                  Tag("status", "success"),
                ),
              ).increment()
            Response.fromPayloadReceivedAsJson(it.payload.executionPayload.toDomainExecutionPayload())
          }

          it.errorMessage != null -> {
            counterFactory
              .create(
                listOf(
                  Tag("method", "getPayload"),
                  Tag("status", "failure"),
                ),
              ).increment()
            Response.fromErrorMessage(it.errorMessage)
          }

          else ->
            throw IllegalStateException("Failed to get payload!")
        }
      }

  override fun newPayload(executionPayload: ExecutionPayload): SafeFuture<Response<PayloadStatusV1>> =
    timerFactory
      .create(listOf(Tag("method", "newPayload")))
      .captureTimeSafeFuture(
        web3jEngineClient
          .newPayloadV4(executionPayload.toExecutionPayloadV3(), emptyList(), Bytes32.ZERO, emptyList()),
      ).thenApply {
        if (it.payload != null) {
          counterFactory
            .create(
              listOf(
                Tag("method", "newPayload"),
                Tag("status", "success"),
              ),
            ).increment()
          Response.fromPayloadReceivedAsJson(it.payload)
        } else {
          counterFactory
            .create(
              listOf(
                Tag("method", "newPayload"),
                Tag("status", "failure"),
              ),
            ).increment()
          Response.fromErrorMessage(it.errorMessage)
        }
      }

  override fun forkChoiceUpdate(
    forkChoiceState: ForkChoiceStateV1,
    payloadAttributes: PayloadAttributesV1?,
  ): SafeFuture<Response<ForkChoiceUpdatedResult>> =
    timerFactory
      .create(listOf(Tag("method", "newPayload")))
      .captureTimeSafeFuture(
        web3jEngineClient.forkChoiceUpdatedV3(forkChoiceState, Optional.ofNullable(payloadAttributes?.toV3())),
      ).thenPeek {
        when {
          it.payload != null ->
            counterFactory
              .create(
                listOf(
                  Tag("method", "forkChoiceUpdate"),
                  Tag("status", "success"),
                ),
              ).increment()
          else ->
            counterFactory
              .create(
                listOf(
                  Tag("method", "forkChoiceUpdate"),
                  Tag("status", "failure"),
                ),
              ).increment()
        }
      }

  private fun PayloadAttributesV1.toV3(): PayloadAttributesV3 =
    PayloadAttributesV3(
      this.timestamp,
      this.prevRandao,
      this.suggestedFeeRecipient,
      emptyList(),
      Bytes32.ZERO,
    )
}
