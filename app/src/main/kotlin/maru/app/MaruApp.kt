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
package maru.app

import java.time.Clock
import maru.config.FollowersConfig
import maru.config.MaruConfig
import maru.consensus.BlockMetadata
import maru.consensus.ForksSchedule
import maru.consensus.LatestBlockMetadataCache
import maru.consensus.NewBlockHandler
import maru.consensus.NewBlockHandlerMultiplexer
import maru.consensus.NextBlockTimestampProviderImpl
import maru.consensus.OmniProtocolFactory
import maru.consensus.ProtocolStarter
import maru.consensus.ProtocolStarterBlockHandler
import maru.consensus.Web3jMetadataProvider
import maru.consensus.delegated.ElDelegatedConsensusFactory
import maru.consensus.state.FinalizationState
import maru.core.BeaconBlockHeader
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem

class MaruApp(
  config: MaruConfig,
  beaconGenesisConfig: ForksSchedule,
  clock: Clock = Clock.systemUTC(),
) {
  private val log: Logger = LogManager.getLogger(this::class.java)

  init {
    if (config.p2pConfig == null) {
      log.warn("P2P is disabled!")
    }
    if (config.validator == null) {
      log.info("Maru is running in follower-only node")
      throw IllegalArgumentException("Follower-only mode is not supported yet!")
    }
    log.info(config.toString())
  }

  private val ethereumJsonRpcClient =
    Helpers.createWeb3jClient(
      config.sotNode,
    )

  private val asyncMetadataProvider = Web3jMetadataProvider(ethereumJsonRpcClient.eth1Web3j)
  private val lastBlockMetadataCache: LatestBlockMetadataCache =
    LatestBlockMetadataCache(
      asyncMetadataProvider
        .getLatestBlockMetadata()
        .get(),
    )
  private val metadataProviderCacheUpdater: NewBlockHandler =
    NewBlockHandler { beaconBlock ->
      val blockMetadata = BlockMetadata.fromBeaconBlock(beaconBlock)
      lastBlockMetadataCache.updateLatestBlockMetadata(blockMetadata)
    }

  private val nextTargetBlockTimestampProvider =
    NextBlockTimestampProviderImpl(
      clock = clock,
      forksSchedule = beaconGenesisConfig,
    )

  private val metricsSystem = NoOpMetricsSystem()
  private val finalizationStateProviderStub = { _: BeaconBlockHeader ->
    LogManager.getLogger("FinalizationStateProvider").debug("fetching the latest finalized state")
    val latestBlockHash = lastBlockMetadataCache.getLatestBlockMetadata().blockHash
    FinalizationState(latestBlockHash, latestBlockHash)
  }

  private val protocolStarter =
    let {
      val metadataCacheUpdaterHandlerEntry = "latest block metadata updater" to metadataProviderCacheUpdater
      val delegatedConsensusNewBlockHandler =
        NewBlockHandlerMultiplexer(
          mapOf(metadataCacheUpdaterHandlerEntry),
        )
      val qbftConsensusNewBlockHandler =
        NewBlockHandlerMultiplexer(createFollowerHandlers(config.followers) + metadataCacheUpdaterHandlerEntry)
      ProtocolStarter(
        forksSchedule = beaconGenesisConfig,
        protocolFactory =
          OmniProtocolFactory(
            elDelegatedConsensusFactory =
              ElDelegatedConsensusFactory(
                ethereumJsonRpcClient = ethereumJsonRpcClient.eth1Web3j,
                newBlockHandler = delegatedConsensusNewBlockHandler,
              ),
            qbftConsensusFactory =
              QbftProtocolFactoryWithBeaconChainInitialization(
                maruConfig = config,
                metricsSystem = metricsSystem,
                metadataProvider = lastBlockMetadataCache,
                finalizationStateProvider = finalizationStateProviderStub,
                executionLayerClient = ethereumJsonRpcClient.eth1Web3j,
                nextTargetBlockTimestampProvider = nextTargetBlockTimestampProvider,
                newBlockHandler = qbftConsensusNewBlockHandler,
              ),
          ),
        metadataProvider = lastBlockMetadataCache,
        nextBlockTimestampProvider = nextTargetBlockTimestampProvider,
      ).also {
        delegatedConsensusNewBlockHandler.addHandler("protocol starter", ProtocolStarterBlockHandler(it))
        qbftConsensusNewBlockHandler.addHandler("qbft", ProtocolStarterBlockHandler(it))
      }
    }

  private fun createFollowerHandlers(followers: FollowersConfig): Map<String, NewBlockHandler> =
    followers.followers
      .mapValues {
        Helpers.createFollowerBlockImporter(it.value, lastBlockMetadataCache)
      }

  fun start() {
    protocolStarter.start()
    log.info("Maru is up")
  }

  fun stop() {
    protocolStarter.stop()
    log.info("Maru is down")
  }
}
