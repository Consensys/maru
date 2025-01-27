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
import java.util.concurrent.TimeUnit
import kotlin.math.max
import maru.consensus.ForksSchedule
import maru.executionlayer.manager.BlockMetadata
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.events.BlockTimerExpiry
import org.hyperledger.besu.consensus.common.bft.statemachine.BftEventHandler
import tech.pegasys.teku.infrastructure.async.SafeFuture

class TimeDrivenEventProducer(
  private val forksSchedule: ForksSchedule<Any>,
  private val eventHandler: BftEventHandler,
  private val blockMetadataProvider: () -> BlockMetadata,
  private val clock: Clock,
) {
  private val log: Logger = LogManager.getLogger(this::class.java)

  @Volatile
  private var currentTask: SafeFuture<Unit>? = null

  @Synchronized
  fun start() {
    if (currentTask == null) {
      handleTick()
    } else {
      throw IllegalStateException("Timer has already been started!")
    }
  }

  @Synchronized
  private fun handleTick() {
    val lastBlockMetadata = blockMetadataProvider()
    val nextBlockNumber = lastBlockMetadata.blockNumber + 1u
    val nextBlockConfig = forksSchedule.getForkByNumber(nextBlockNumber)

    if (!currentTask!!.isDone) {
      log.warn("Current task isn't done. Scheduling the next one, but results may be unexpected!")
    }
    stop()
    when (nextBlockConfig) {
      is DummyConsensusConfig -> {
        scheduleNextTask(lastBlockMetadata, nextBlockNumber, nextBlockConfig)
      }

      else -> {
        log.warn("Next fork isn't a Dummy Consensus one. Stopping ${this.javaClass.name}")
      }
    }
  }

  private fun scheduleNextTask(
    lastBlockMetadata: BlockMetadata,
    nextBlockNumber: ULong,
    nextBlockConfig: DummyConsensusConfig,
  ) {
    val currentTime = clock.millis().toULong()
    val delay = nextBlockDelay(currentTime, lastBlockMetadata.timestamp, nextBlockConfig.blockTimeMillis).toLong()

    val executor =
      if (delay > 0) {
        SafeFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
      } else {
        // For some reason it's not static, so it is what it is 🤷
        SafeFuture.COMPLETE.defaultExecutor()
      }

    currentTask =
      SafeFuture.of(
        SafeFuture
          .runAsync(
            {
              val consensusRoundIdentifier =
                ConsensusRoundIdentifier(nextBlockNumber.toLong(), nextBlockNumber.toInt())
              eventHandler.handleBlockTimerExpiry(BlockTimerExpiry(consensusRoundIdentifier))
              handleTick()
            },
            executor,
          ).thenApply { },
      )
  }

  /** Returns required block production delay between the last block time and next block time according to the
   * effective block period.
   *
   * Returns 0 if next block is overdue
   */
  internal fun nextBlockDelay(
    currentTime: ULong,
    lastBlockTimestamp: ULong,
    nextBlockPeriod: UInt,
  ): ULong {
    val expiryTime = lastBlockTimestamp + nextBlockPeriod / 1000UL
    return max(expiryTime.toLong() - currentTime.toLong(), 0).toULong()
  }

  @Synchronized
  fun stop() {
    if (currentTask != null) {
      currentTask!!.cancel(false)
      currentTask = null
    } else {
      throw IllegalStateException("EventProducer hasn't been started to stop it!")
    }
  }
}
