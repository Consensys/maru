/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.util.UUID
import maru.config.ApiEndpointConfig
import maru.consensus.ElFork
import maru.consensus.ForksSchedule
import maru.consensus.NewBlockHandler
import maru.consensus.NewBlockHandlerMultiplexer
import maru.consensus.blockimport.ElForkAwareBlockImporter
import maru.consensus.blockimport.FollowerBeaconBlockImporter
import maru.consensus.blockimport.SetHeadOnlyBlockImporter
import maru.consensus.state.FinalizationProvider
import maru.core.Protocol
import maru.database.BeaconChain
import maru.executionlayer.ExecutionLayerFactory.buildExecutionLayerManager
import maru.executionlayer.manager.ExecutionLayerManager
import maru.web3j.TekuWeb3JClientFactory
import net.consensys.linea.metrics.MetricsFacade
import org.apache.logging.log4j.LogManager
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

  fun createBlockImportHandlers(
    elFork: ElFork,
    metricsFacade: MetricsFacade,
    finalizationStateProvider: FinalizationProvider,
    followerELNodeEngineApiWeb3JClients: Map<String, Web3JClient>,
  ): NewBlockHandlerMultiplexer {
    val elFollowersNewBlockHandlerMap =
      followerELNodeEngineApiWeb3JClients.mapValues { (followerName, web3JClient) ->
        val elFollowerExecutionLayerManager =
          buildExecutionLayerManager(
            web3JEngineApiClient = web3JClient,
            elFork = elFork,
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

  fun createForkAwareBlockImportHandlers(
    forksSchedule: ForksSchedule,
    metricsFacade: MetricsFacade,
    followerELNodeEngineApiWeb3JClients: Map<String, Web3JClient>,
    finalizationProvider: FinalizationProvider,
  ): NewBlockHandlerMultiplexer {
    val elFollowersNewBlockHandlerMap =
      followerELNodeEngineApiWeb3JClients.mapValues { (followerName, web3JClient) ->
        val elManagerMap =
          ElFork.entries.associateWith { elFork ->
            buildExecutionLayerManager(
              web3JEngineApiClient = web3JClient,
              elFork = elFork,
              metricsFacade = metricsFacade,
            )
          }
        ElForkAwareBlockImporter(
          forksSchedule = forksSchedule,
          elManagerMap = elManagerMap,
          importerName = followerName,
          finalizationProvider = finalizationProvider,
        )
      }
    return NewBlockHandlerMultiplexer(elFollowersNewBlockHandlerMap)
  }

  fun buildElImporterHandlers(
    ownExecutionLayerManager: ExecutionLayerManager?,
    ownHandlerName: String,
    followerELNodeEngineApiWeb3JClients: Map<String, Web3JClient>,
    elFork: ElFork,
    finalizationStateProvider: FinalizationProvider,
    metricsFacade: MetricsFacade,
    payloadValidationEnabled: Boolean = false,
  ): Map<String, NewBlockHandler<*>> =
    buildMap {
      followerELNodeEngineApiWeb3JClients.forEach { (name, client) ->
        put(
          name,
          FollowerBeaconBlockImporter.create(
            executionLayerManager = buildExecutionLayerManager(client, elFork, metricsFacade),
            finalizationStateProvider = finalizationStateProvider,
            importerName = name,
          ),
        )
      }
      ownExecutionLayerManager?.let {
        put(
          ownHandlerName,
          if (payloadValidationEnabled) {
            SetHeadOnlyBlockImporter.create(
              executionLayerManager = it,
              finalizationStateProvider = finalizationStateProvider,
              importerName = ownHandlerName,
            )
          } else {
            FollowerBeaconBlockImporter.create(
              executionLayerManager = it,
              finalizationStateProvider = finalizationStateProvider,
              importerName = ownHandlerName,
            )
          },
        )
      }
    }
}

fun Protocol.subscribeElSync(
  beaconChain: BeaconChain,
  handlers: Map<String, NewBlockHandler<*>>,
): Protocol {
  if (handlers.isEmpty()) return this
  val log = LogManager.getLogger("maru.app.ElSyncSubscriber")
  val multiplexer = NewBlockHandlerMultiplexer(handlers)
  val subscriberId = "el-sync-${UUID.randomUUID()}"
  beaconChain.addSyncSubscriber(subscriberId) { block ->
    multiplexer.handleNewBlock(block.beaconBlock).whenComplete { _, error ->
      if (error != null) {
        log.warn(
          "EL sync failed for block={}: {}",
          block.beaconBlock.beaconBlockHeader.number,
          error.message,
        )
      }
    }
  }
  return ProtocolWithBeaconChainObserver(this, beaconChain, subscriberId)
}
