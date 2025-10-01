/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import maru.config.ApiEndpointConfig
import maru.config.consensus.ElFork
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.CallAndForgetFutureMultiplexer
import maru.consensus.NewBlockHandlerMultiplexer
import maru.consensus.blockimport.FollowerBeaconBlockImporter
import maru.consensus.state.FinalizationProvider
import maru.executionlayer.client.CancunWeb3JJsonRpcExecutionLayerEngineApiClient
import maru.executionlayer.client.ExecutionLayerEngineApiClient
import maru.executionlayer.client.ParisWeb3JJsonRpcExecutionLayerEngineApiClient
import maru.executionlayer.client.PragueWeb3JJsonRpcExecutionLayerEngineApiClient
import maru.executionlayer.client.ShanghaiWeb3JJsonRpcExecutionLayerEngineApiClient
import maru.executionlayer.manager.ExecutionLayerManager
import maru.executionlayer.manager.JsonRpcExecutionLayerManager
import maru.web3j.TekuWeb3JClientFactory
import net.consensys.linea.metrics.MetricsFacade
import org.apache.logging.log4j.Logger
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JClient

object Helpers {
  fun createWeb3jClient(
    apiEndpointConfig: ApiEndpointConfig,
    log: Logger,
  ): Web3JClient =
    TekuWeb3JClientFactory
      .create(
        endpoint = apiEndpointConfig.endpoint,
        jwtPath = apiEndpointConfig.jwtSecretPath,
        timeout = apiEndpointConfig.timeout,
        log = log,
      )

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
      ElFork.Paris ->
        ParisWeb3JJsonRpcExecutionLayerEngineApiClient(
          web3jClient = web3JEngineApiClient,
          metricsFacade = metricsFacade,
        )

      ElFork.Shanghai ->
        ShanghaiWeb3JJsonRpcExecutionLayerEngineApiClient(
          web3jClient = web3JEngineApiClient,
          metricsFacade = metricsFacade,
        )

      ElFork.Cancun ->
        CancunWeb3JJsonRpcExecutionLayerEngineApiClient(
          web3jClient = web3JEngineApiClient,
          metricsFacade = metricsFacade,
        )

      ElFork.Prague ->
        PragueWeb3JJsonRpcExecutionLayerEngineApiClient(
          web3jClient = web3JEngineApiClient,
          metricsFacade = metricsFacade,
        )
    }

  fun createBlockImportHandlers(
    qbftConsensusConfig: QbftConsensusConfig,
    metricsFacade: MetricsFacade,
    finalizationStateProvider: FinalizationProvider,
    followerELNodeEngineApiWeb3JClients: Map<String, Web3JClient>,
  ): NewBlockHandlerMultiplexer {
    val elFollowersNewBlockHandlerMap =
      followerELNodeEngineApiWeb3JClients.mapValues { (followerName, web3JClient) ->
        val elFollowerExecutionLayerManager =
          buildExecutionLayerManager(
            web3JEngineApiClient = web3JClient,
            elFork = qbftConsensusConfig.elFork,
            metricsFacade = metricsFacade,
          )
        FollowerBeaconBlockImporter.create(
          executionLayerManager = elFollowerExecutionLayerManager,
          finalizationStateProvider = finalizationStateProvider,
          importerName = followerName,
        )
      }
    return NewBlockHandlerMultiplexer(elFollowersNewBlockHandlerMap)
  }
}
