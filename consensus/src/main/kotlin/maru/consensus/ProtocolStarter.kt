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
package maru.consensus

import java.util.concurrent.atomic.AtomicReference
import maru.core.BeaconBlock
import maru.core.Protocol
import maru.executionlayer.client.MetadataProvider
import maru.executionlayer.manager.BlockMetadata
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

class ProtocolStarterBlockHandler(
  private val protocolStarter: ProtocolStarter,
) : NewBlockHandler {
  override fun handleNewBlock(block: BeaconBlock) {
    val blockMetadata =
      BlockMetadata(
        block.beaconBlockBody.executionPayload.blockNumber,
        block.beaconBlockHeader.hash,
        block.beaconBlockHeader.timestamp.toLong(),
      )
    protocolStarter.handleNewBlock(blockMetadata)
  }
}

class ProtocolStarter(
  private val forksSchedule: ForksSchedule,
  private val protocolFactory: ProtocolFactory,
  private val metadataProvider: MetadataProvider,
  private val nextBlockTimestampProvider: NextBlockTimestampProvider,
) : Protocol {
  data class ProtocolWithFork(
    val protocol: Protocol,
    val fork: ForkSpec,
  ) {
    override fun toString(): String = "protocol=${protocol.javaClass.simpleName}, fork=$fork"
  }

  private val log: Logger = LogManager.getLogger(this::class.java)

  internal val currentProtocolWithForkReference: AtomicReference<ProtocolWithFork> = AtomicReference()

  @Synchronized
  fun handleNewBlock(block: BlockMetadata) {
    log.debug("New block number={} received", { block.blockNumber })

    val nextBlockTimestamp = nextBlockTimestampProvider.nextTargetBlockUnixTimestamp(block.unixTimestampSeconds)
    val nextForkSpec = forksSchedule.getForkByTimestamp(nextBlockTimestamp)

    val currentProtocolWithFork = currentProtocolWithForkReference.get()
    if (currentProtocolWithFork?.fork != nextForkSpec) {
      val newProtocol: Protocol = protocolFactory.create(nextForkSpec)

      val newProtocolWithFork =
        ProtocolWithFork(
          newProtocol,
          nextForkSpec,
        )
      log.debug("Switching from {} to protocol {}", currentProtocolWithFork, newProtocolWithFork)
      currentProtocolWithForkReference.set(
        newProtocolWithFork,
      )
      currentProtocolWithFork?.protocol?.stop()
      newProtocol.start()
    } else {
      log.trace("Block {} was produced, but the fork switch isn't required", { block.blockNumber })
    }
  }

  override fun start() {
    val latestBlock = metadataProvider.getLatestBlockMetadata().get()
    handleNewBlock(latestBlock)
  }

  override fun stop() {
    currentProtocolWithForkReference.get().protocol.stop()
    currentProtocolWithForkReference.set(null)
  }
}
