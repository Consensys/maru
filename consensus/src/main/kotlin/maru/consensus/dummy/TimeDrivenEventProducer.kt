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
import kotlin.time.Duration
import maru.consensus.ForksSchedule
import maru.executionlayer.manager.BlockMetadata
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.events.BlockTimerExpiry
import org.hyperledger.besu.consensus.common.bft.statemachine.BftEventHandler
import tech.pegasys.teku.infrastructure.async.SafeFuture

class TimeDrivenEventProducer(
  private val forksSchedule: ForksSchedule,
  private val eventHandler: BftEventHandler,
  private val blockMetadataProvider: () -> BlockMetadata,
  private val clock: Clock,
  private val config: Config,
) {
  companion object {
    /** Returns required block production delay between the last block time and next block time according to the
     * effective block period.
     *
     * Returns 0 if next block is overdue
     */
    internal fun nextBlockDelay(
      currentTimestampMillis: Long,
      lastBlockTimestampSeconds: Long,
      nextBlockPeriodMillis: Int,
    ): ULong {
      val expiryTime = lastBlockTimestampSeconds * 1000 + nextBlockPeriodMillis
      return (expiryTime - currentTimestampMillis).toULong()
    }
  }

  data class Config(
    val communicationMargin: Duration,
  )

  private val log: Logger = LogManager.getLogger(this::class.java)

  @Volatile
  private var currentTask: SafeFuture<Unit>? = null

  @Synchronized
  fun start() {
    if (currentTask == null) {
      SafeFuture.runAsync {
        // For the first ever tick EL will need some time to prepare a block in any case, thus forcing delay
        handleTick(forceDelay = true)
      }
    } else {
      throw IllegalStateException("Timer has already been started!")
    }
  }

  @Synchronized
  private fun handleTick(forceDelay: Boolean = false) {
    val lastBlockMetadata = blockMetadataProvider()
    val nextBlockNumber = lastBlockMetadata.blockNumber + 1u
    val nextBlockConfig = forksSchedule.getForkByNumber(nextBlockNumber)

    log.debug("currentTimestamp={} nextBlockNumber={}", clock.millis(), nextBlockNumber)

    if (currentTask != null) {
      if (!currentTask!!.isDone) {
        log.warn("Current task isn't done. Scheduling the next one, but results may be unexpected!")
      }
      stop()
    }
    when (nextBlockConfig) {
      is DummyConsensusConfig -> {
        scheduleNextTask(
          lastBlockMetadata = lastBlockMetadata,
          nextBlockNumber = nextBlockNumber,
          nextBlockConfig = nextBlockConfig,
          forceDelay = forceDelay,
        )
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
    forceDelay: Boolean,
  ) {
    val currentTime = clock.millis()

    val delayMillis: Long =
      if (forceDelay) {
        nextBlockDelay(
          currentTimestampMillis = currentTime,
          lastBlockTimestampSeconds = (currentTime / 1000) + 1,
          nextBlockPeriodMillis = nextBlockConfig.blockTimeMillis.toInt(),
        ).toLong()
      } else {
        nextBlockDelay(
          currentTimestampMillis = currentTime,
          lastBlockTimestampSeconds = lastBlockMetadata.timestamp.toLong(),
          nextBlockPeriodMillis = nextBlockConfig.blockTimeMillis.toInt(),
        ).toLong()
      }
    log.debug("Next target timestamp: {}", { currentTime + delayMillis })

    val executor =
      SafeFuture.delayedExecutor(delayMillis - config.communicationMargin.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    currentTask =
      SafeFuture
        .of(
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
        ).whenException {
          log.error(it.message, it)
          handleTick(forceDelay = true)
        }
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
