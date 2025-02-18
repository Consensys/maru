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
import java.util.concurrent.atomic.AtomicReference
import maru.app.config.MaruConfig
import maru.consensus.ConsensusConfiguration
import maru.consensus.ForksSchedule
import maru.consensus.NewBlockHandler
import maru.consensus.delegated.ElDelegatedConsensus
import maru.consensus.delegated.Mapper
import maru.consensus.dummy.DummyConsensusConfig
import maru.core.Protocol
import maru.executionlayer.client.ExecutionLayerClient
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.ethereum.core.Block
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter

class ProtocolStarter(
  private val forksSchedule: ForksSchedule,
  private val clock: Clock,
  private val config: MaruConfig,
  private val executionLayerClient: ExecutionLayerClient,
  private val ethereumJsonRpcClient: Web3j,
) : NewBlockHandler,
  Protocol {
  data class ProtocolWithConfig(
    val protocol: Protocol,
    val config: ConsensusConfiguration,
  )

  private val log: Logger = LogManager.getLogger(this::class.java)

  private val currentProtocolWithConfig: AtomicReference<ProtocolWithConfig> = AtomicReference()

  @Synchronized
  override fun handleNewBlock(block: Block) {
    log.debug("New block ${block.header.number} received")
    val latestBlockNumber = block.header.number
    val nextForkSpec = forksSchedule.getForkByNumber(latestBlockNumber.toULong() + 1UL)

    val currentProtocol = currentProtocolWithConfig.get()
    if (currentProtocol?.config != nextForkSpec) {
      val newProtocol: Protocol =
        when (nextForkSpec) {
          is DummyConsensusConfig -> {
            require(config.dummyConsensusOptions != null) {
              "Next fork is dummy consensus one, but dummyConsensusOptions are undefined!"
            }

            DummyConsensusProtocolBuilder
              .build(
                forksSchedule = forksSchedule,
                clock = clock,
                minTimeTillNextBlock = config.executionClientConfig.minTimeBetweenGetPayloadAttempts,
                dummyConsensusOptions = config.dummyConsensusOptions,
                executionLayerClient = executionLayerClient,
                onNewBlockHandler = this,
              )
          }

          is ElDelegatedConsensus.Config -> {
            ElDelegatedConsensus(
              ethereumJsonRpcClient = ethereumJsonRpcClient,
              onNewBlock = this,
              config = nextForkSpec,
            )
          }

          else -> {
            throw IllegalArgumentException("Next fork $nextForkSpec is unknown!")
          }
        }

      val newProtocolWithConfig =
        ProtocolWithConfig(
          newProtocol,
          nextForkSpec,
        )
      log.debug("Switching from $currentProtocol to protocol $newProtocolWithConfig")
      currentProtocolWithConfig.set(
        newProtocolWithConfig,
      )
      currentProtocol?.protocol?.stop()
      newProtocol.start()
    } else {
      log.trace("Block ${block.header.number} was produced, but the fork switch isn't required")
    }
  }

  override fun start() {
    val latestBlock =
      ethereumJsonRpcClient
        .ethGetBlockByNumber(
          DefaultBlockParameter.valueOf("latest"),
          false,
        ).send()
    handleNewBlock(Mapper.mapWeb3jBlockToBesuBlock(latestBlock.block))
  }

  override fun stop() {
    currentProtocolWithConfig.get().protocol.stop()
    currentProtocolWithConfig.set(null)
  }
}
