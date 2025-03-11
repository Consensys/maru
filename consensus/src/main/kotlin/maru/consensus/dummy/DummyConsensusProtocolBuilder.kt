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
package maru.consensus.dummy

import java.time.Clock
import kotlin.time.Duration
import maru.config.DummyConsensusOptions
import maru.config.ExecutionClientConfig
import maru.consensus.ElFork
import maru.consensus.EngineApiBlockCreator
import maru.consensus.ForksSchedule
import maru.consensus.NewBlockHandler
import maru.executionlayer.client.ExecutionLayerClient
import maru.executionlayer.client.MetadataProvider
import maru.executionlayer.client.PragueWeb3jJsonRpcExecutionLayerClient
import maru.executionlayer.manager.FeeRecipientProvider
import maru.executionlayer.manager.JsonRpcExecutionLayerManager
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JExecutionEngineClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3jClientBuilder
import tech.pegasys.teku.infrastructure.time.SystemTimeProvider

object DummyConsensusProtocolBuilder {
  class DummyConsensusFeeRecipientProvider(
    private val forksSchedule: ForksSchedule,
  ) : FeeRecipientProvider {
    override fun getFeeRecipient(timestamp: Long): ByteArray {
      val nextExpectedFork = forksSchedule.getNextForkByTimestamp(timestamp)
      return (
        nextExpectedFork.configuration as DummyConsensusConfig
      ).feeRecipient
    }
  }

  fun build(
    forksSchedule: ForksSchedule,
    clock: Clock,
    nextBlockTimestampProvider: NextBlockTimestampProvider,
    dummyConsensusOptions: DummyConsensusOptions,
    executionClientConfig: ExecutionClientConfig,
    metadataProvider: MetadataProvider,
    onNewBlockHandler: NewBlockHandler,
    effectiveFork: ElFork,
  ): TimeDrivenEventProducer {
    val executionLayerClient =
      buildExecutionEngineClient(
        executionClientConfig.engineApiJsonRpcEndpoint.toString(),
        effectiveFork,
      )
    val jsonRpcExecutionLayerManager =
      JsonRpcExecutionLayerManager
        .create(
          executionLayerClient = executionLayerClient,
          metadataProvider = metadataProvider,
          feeRecipientProvider = DummyConsensusFeeRecipientProvider(forksSchedule),
          payloadValidator = EmptyBlockValidator,
        ).get()
    val latestBlockMetadata = jsonRpcExecutionLayerManager.latestBlockMetadata()
    val latestBlockHash = latestBlockMetadata.blockHash

    val finalizationState = FinalizationState(latestBlockHash, latestBlockHash)
    val dummyConsensusState =
      DummyConsensusState(
        clock = clock,
        finalizationState_ = finalizationState,
        latestBlockHash_ = latestBlockHash,
      )

    val blockCreator =
      EngineApiBlockCreator(
        manager = jsonRpcExecutionLayerManager,
        state = dummyConsensusState,
        blockHeaderFunctions = MainnetBlockHeaderFunctions(),
        nextBlockTimestamp = nextBlockTimestampProvider.nextTargetBlockUnixTimestamp(latestBlockMetadata),
      )
    val eventHandler =
      DummyConsensusEventHandler(
        executionLayerManager = jsonRpcExecutionLayerManager,
        blockCreator = blockCreator,
        nextBlockTimestampProvider = nextBlockTimestampProvider,
        onNewBlock = onNewBlockHandler,
      )
    return TimeDrivenEventProducer(
      forksSchedule = forksSchedule,
      eventHandler = eventHandler,
      blockMetadataProvider = jsonRpcExecutionLayerManager::latestBlockMetadata,
      nextBlockTimestampProvider = nextBlockTimestampProvider,
      clock = clock,
      config = TimeDrivenEventProducer.Config(dummyConsensusOptions.communicationMargin),
    )
  }

  private fun buildExecutionEngineClient(
    endpoint: String,
    elFork: ElFork,
  ): ExecutionLayerClient {
    val web3JEngineApiClient: Web3JClient =
      Web3jClientBuilder()
        .endpoint(endpoint)
        .timeout(java.time.Duration.ofMinutes(1))
        .timeProvider(SystemTimeProvider.SYSTEM_TIME_PROVIDER)
        .executionClientEventsPublisher { }
        .build()
    val web3jExecutionLayerClient = Web3JExecutionEngineClient(web3JEngineApiClient)
    return when (elFork) {
      ElFork.Prague -> PragueWeb3jJsonRpcExecutionLayerClient(web3jExecutionLayerClient)
    }
  }
}
