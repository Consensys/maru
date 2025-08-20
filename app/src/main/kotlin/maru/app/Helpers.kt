/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.util.Optional
import java.util.UUID
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import maru.config.ApiEndpointConfig
import maru.config.consensus.ElFork
import maru.executionlayer.client.ExecutionLayerEngineApiClient
import maru.executionlayer.client.PragueWeb3JJsonRpcExecutionLayerEngineApiClient
import maru.executionlayer.client.ShanghaiWeb3JJsonRpcExecutionLayerEngineApiClient
import maru.executionlayer.manager.ExecutionLayerManager
import maru.executionlayer.manager.JsonRpcExecutionLayerManager
import net.consensys.linea.metrics.MetricsFacade
import tech.pegasys.teku.ethereum.executionclient.auth.JwtConfig
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3jClientBuilder
import tech.pegasys.teku.infrastructure.time.SystemTimeProvider

object Helpers {
  private fun wrapJwtPath(jwtPath: String?): Optional<JwtConfig> {
    val jwtConfigPath = Optional.ofNullable(jwtPath)
    return JwtConfig.createIfNeeded(
      /* needed = */ jwtConfigPath.isPresent,
      jwtConfigPath,
      Optional.of(UUID.randomUUID().toString()),
      Path("/dev/null"), // Teku's API limitation. Would be good to clean it
    )
  }

  fun createWeb3jClient(
    apiEndpointConfig: ApiEndpointConfig,
    timeout: Duration,
  ): Web3JClient =
    Web3jClientBuilder()
      .timeout(timeout.toJavaDuration())
      .endpoint(apiEndpointConfig.endpoint.toString())
      .jwtConfigOpt(wrapJwtPath(apiEndpointConfig.jwtSecretPath))
      .timeProvider(SystemTimeProvider.SYSTEM_TIME_PROVIDER)
      .executionClientEventsPublisher {}
      .build()

  fun buildExecutionLayerManager(
    web3JEngineApiClient: Web3JClient,
    elFork: ElFork,
    metricsFacade: MetricsFacade,
  ): ExecutionLayerManager =
    JsonRpcExecutionLayerManager(
      executionLayerEngineApiClient =
        buildExecutionEngineClient(
          web3JEngineApiClient = web3JEngineApiClient,
          elFork = elFork,
          metricsFacade = metricsFacade,
        ),
    )

  fun buildExecutionEngineClient(
    web3JEngineApiClient: Web3JClient,
    elFork: ElFork,
    metricsFacade: MetricsFacade,
  ): ExecutionLayerEngineApiClient =
    when (elFork) {
      ElFork.Shanghai ->
        ShanghaiWeb3JJsonRpcExecutionLayerEngineApiClient(
          web3jClient = web3JEngineApiClient,
          metricsFacade = metricsFacade,
        )

      ElFork.Prague ->
        PragueWeb3JJsonRpcExecutionLayerEngineApiClient(
          web3jClient = web3JEngineApiClient,
          metricsFacade = metricsFacade,
        )
    }
}
