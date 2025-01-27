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
import maru.app.config.ExecutionClientConfig
import maru.consensus.EngineApiBlockCreator
import maru.consensus.ForksSchedule
import maru.consensus.dummy.DummyConsensusEventHandler
import maru.consensus.dummy.DummyConsensusState
import maru.consensus.dummy.FinalizationState
import maru.consensus.dummy.TimeDrivenEventProducer
import maru.executionlayer.client.ExecutionLayerClient
import maru.executionlayer.client.Web3jJsonRpcExecutionLayerClient
import maru.executionlayer.manager.JsonRpcExecutionLayerManager
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JExecutionEngineClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3jClientBuilder

object DummyConsensusProtocolBuilder {
  fun build(
    forksSchedule: ForksSchedule<Any>,
    clock: Clock,
    executionClientConfig: ExecutionClientConfig,
    feesRecipient: ByteArray,
  ): TimeDrivenEventProducer {
    val web3JClient =
      Web3jClientBuilder()
        .endpoint(executionClientConfig.endpoint.toString())
        .build()
    val web3jExecutionLayerClient = Web3JExecutionEngineClient(web3JClient)
    val executionLayerClient: ExecutionLayerClient =
      Web3jJsonRpcExecutionLayerClient(web3jExecutionLayerClient, web3JClient)

    val jsonRpcExecutionLayerManager =
      JsonRpcExecutionLayerManager
        .create(
          executionLayerClient = executionLayerClient,
          newBlockTimestampProvider = { clock.millis().toULong() / 1000u },
          feeRecipientProvider = { feesRecipient },
        ).get()
    val latestBlockHash = jsonRpcExecutionLayerManager.latestBlockMetadata().blockHash
    val finalizationState = FinalizationState(latestBlockHash, latestBlockHash)
    val dummyConsensusState =
      DummyConsensusState(
        clock = clock,
        finalizationState_ = finalizationState,
        latestBlockHash_ = latestBlockHash,
      )
    val blockCreator =
      EngineApiBlockCreator(jsonRpcExecutionLayerManager, dummyConsensusState, MainnetBlockHeaderFunctions())
    val eventHandler =
      DummyConsensusEventHandler(
        state = dummyConsensusState,
        executionLayerManager = jsonRpcExecutionLayerManager,
        blockCreator = blockCreator,
        onNewBlock = {},
      )
    return TimeDrivenEventProducer(
      forksSchedule,
      eventHandler,
      jsonRpcExecutionLayerManager::latestBlockMetadata,
      clock,
    )
  }
}
