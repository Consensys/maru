/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import maru.core.BeaconBlock
import maru.core.Protocol
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import tech.pegasys.teku.infrastructure.async.SafeFuture

class ProtocolStarterBlockHandler(
  private val protocolStarter: ProtocolStarter,
) : NewBlockHandler<Unit> {
  override fun handleNewBlock(beaconBlock: BeaconBlock): SafeFuture<Unit> {
    val elBlockMetadata =
      ElBlockMetadata(
        beaconBlock.beaconBlockBody.executionPayload.blockNumber,
        beaconBlock.beaconBlockHeader.hash,
        beaconBlock.beaconBlockHeader.timestamp.toLong(),
      )
    protocolStarter.handleNewBlock(elBlockMetadata)
    return SafeFuture.completedFuture(Unit)
  }
}

class ProtocolStarter(
  private val forksSchedule: ForksSchedule,
  private val protocolFactory: ProtocolFactory,
  private val elMetadataProvider: ElMetadataProvider, // TODO: we should probably replace it with BeaconChain
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
  fun handleNewBlock(block: ElBlockMetadata) {
    log.debug("New block number={} received", { block.blockNumber })

    val nextBlockTimestamp = nextBlockTimestampProvider.nextTargetBlockUnixTimestamp(block.unixTimestampSeconds)
    val nextForkSpec = forksSchedule.getForkByTimestamp(nextBlockTimestamp)

    val currentProtocolWithFork = currentProtocolWithForkReference.get()
    if (currentProtocolWithFork?.fork != nextForkSpec) {
      log.debug("Switching from forkSpec={} to newForkFpec={}", currentProtocolWithFork?.fork, nextForkSpec)
      val newProtocol: Protocol = protocolFactory.create(nextForkSpec)

      val newProtocolWithFork =
        ProtocolWithFork(
          newProtocol,
          nextForkSpec,
        )
      log.debug("Switched from {} to protocol {}", currentProtocolWithFork, newProtocolWithFork)
      currentProtocolWithForkReference.set(
        newProtocolWithFork,
      )
      currentProtocolWithFork?.protocol?.stop()

      // Wait until timestamp is reached before starting new protocol
      val timeTillFork = max((nextBlockTimestamp * 1000L) - System.currentTimeMillis(), 0)
      log.debug("Waiting for {} ms until fork switch", timeTillFork)
      Thread.sleep(timeTillFork)

      newProtocol.start()
      log.debug("stated new protocol {}", newProtocol)
    } else {
      log.trace("Block {} was produced, but the fork switch isn't required", { block.blockNumber })
    }
  }

  override fun start() {
    val latestBlock = elMetadataProvider.getLatestBlockMetadata()
    handleNewBlock(latestBlock)
  }

  override fun stop() {
    currentProtocolWithForkReference.get().protocol.stop()
    currentProtocolWithForkReference.set(null)
  }
}
