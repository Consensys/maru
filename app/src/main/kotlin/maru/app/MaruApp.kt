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
import java.time.Duration
import maru.config.MaruConfig
import maru.consensus.ForksSchedule
import maru.consensus.MetadataOnlyHandlerAdapter
import maru.consensus.NewBlockHandlerMultiplexer
import maru.consensus.NextBlockTimestampProviderImpl
import maru.consensus.OmniProtocolFactory
import maru.consensus.ProtocolStarter
import maru.consensus.delegated.ElDelegatedConsensusFactory
import maru.consensus.state.FinalizationState
import maru.core.BeaconBlockHeader
import maru.executionlayer.client.Web3jMetadataProvider
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3jClientBuilder
import tech.pegasys.teku.infrastructure.time.SystemTimeProvider

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
  }

  private val ethereumJsonRpcClient =
    buildJsonRpcClient(
      config.executionClientConfig.ethereumJsonRpcEndpoint
        .toString(),
    )

  private val newBlockHandlerMultiplexer = NewBlockHandlerMultiplexer(emptyMap())

  private val metadataProvider = Web3jMetadataProvider(ethereumJsonRpcClient.eth1Web3j)

  private val nextBlockTimestampProvider =
    NextBlockTimestampProviderImpl(
      clock = clock,
      forksSchedule = beaconGenesisConfig,
    )

  private val metricsSystem = NoOpMetricsSystem()
  private val finalizationStateProviderStub = { _: BeaconBlockHeader ->
    LogManager.getLogger("FinalizationStateProvider").debug("fetching the latest finalized state")
    val latestBlockHash = metadataProvider.getLatestBlockMetadata().get().blockHash
    FinalizationState(latestBlockHash, latestBlockHash)
  }

  private val protocolStarter =
    ProtocolStarter(
      forksSchedule = beaconGenesisConfig,
      protocolFactory =
        OmniProtocolFactory(
          elDelegatedConsensusFactory =
            ElDelegatedConsensusFactory(
              ethereumJsonRpcClient = ethereumJsonRpcClient.eth1Web3j,
              newBlockHandler = newBlockHandlerMultiplexer,
            ),
          qbftConsensusFactory =
            QbftProtocolFactoryWithBeaconChainInitialization(
              maruConfig = config,
              metricsSystem = metricsSystem,
              metadataProvider = metadataProvider,
              finalizationStateProvider = finalizationStateProviderStub,
              executionLayerClient = ethereumJsonRpcClient.eth1Web3j,
            ),
        ),
      metadataProvider = metadataProvider,
      nextBlockTimestampProvider = nextBlockTimestampProvider,
    ).also {
      newBlockHandlerMultiplexer.addHandler("protocol starter", MetadataOnlyHandlerAdapter(it))
    }

  private fun buildJsonRpcClient(endpoint: String): Web3JClient =
    Web3jClientBuilder()
      .endpoint(endpoint)
      .timeout(Duration.ofMinutes(1))
      .timeProvider(SystemTimeProvider.SYSTEM_TIME_PROVIDER)
      .executionClientEventsPublisher { }
      .build()

  fun start() {
    protocolStarter.start()
    log.info("Maru is up")
  }

  fun stop() {
    protocolStarter.stop()
    log.info("Maru is down")
  }
}
