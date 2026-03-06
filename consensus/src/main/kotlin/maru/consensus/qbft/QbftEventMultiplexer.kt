/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.consensus.common.bft.events.BftEvent
import org.hyperledger.besu.consensus.common.bft.events.BftEvents
import org.hyperledger.besu.consensus.common.bft.events.BlockTimerExpiry
import org.hyperledger.besu.consensus.common.bft.events.RoundExpiry
import org.hyperledger.besu.consensus.qbft.core.types.QbftEventHandler
import org.hyperledger.besu.consensus.qbft.core.types.QbftNewChainHead
import org.hyperledger.besu.consensus.qbft.core.types.QbftReceivedMessageEvent

class QbftEventMultiplexer(
  private val eventHandler: QbftEventHandler,
) {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  /**
   * Optional observer called immediately when BLOCK_TIMER_EXPIRY fires, before the event is
   * dispatched to the QBFT state machine. Parameters: (blockNumber, wallClockMs).
   * Intended for benchmarking/testing — records the actual timer-fire wall-clock time so that
   * true consensus latency can be separated from JVM scheduling jitter.
   */
  var onBlockTimerFired: ((blockNumber: Long, wallClockMs: Long) -> Unit)? = null

  /**
   * Called at the beginning of every round before the event is dispatched to the QBFT state
   * machine. Parameters: (blockNumber, roundJustStarted).
   * Used to trigger pre-building for the next round's proposer so they have a payload ready
   * in case the current round results in a ROUND_CHANGE.
   * - Fires with roundJustStarted=0 on BLOCK_TIMER_EXPIRY (round 0 starts)
   * - Fires with roundJustStarted=R+1 on ROUND_EXPIRY for round R (round R+1 starts)
   */
  var onRoundStarted: ((blockNumber: Long, roundJustStarted: Int) -> Unit)? = null

  fun handleEvent(event: BftEvent) {
    val t0 = System.nanoTime()
    // Log at dequeue time so the wall-clock timestamp in the log line marks when Thread-14
    // actually picked this event up from BftEventQueue.  Comparing with the "queued" timestamp
    // logged by QbftMessageProcessor gives the queue-wait latency.
    log.debug("Dequeued event type={}", event.type)
    try {
      log.trace("Received event type: {}, event: {},", event.type, event)
      when (event.type) {
        BftEvents.Type.ROUND_EXPIRY -> {
          val roundExpiry = event as RoundExpiry
          onRoundStarted?.invoke(roundExpiry.view.sequenceNumber, roundExpiry.view.roundNumber + 1)
          eventHandler.handleRoundExpiry(roundExpiry)
        }

        BftEvents.Type.NEW_CHAIN_HEAD -> {
          eventHandler.handleNewBlockEvent(event as QbftNewChainHead)
        }

        BftEvents.Type.BLOCK_TIMER_EXPIRY -> {
          val timerExpiry = event as BlockTimerExpiry
          onBlockTimerFired?.invoke(timerExpiry.roundIdentifier.sequenceNumber, System.currentTimeMillis())
          onRoundStarted?.invoke(timerExpiry.roundIdentifier.sequenceNumber, 0)
          eventHandler.handleBlockTimerExpiry(timerExpiry)
        }

        BftEvents.Type.MESSAGE -> {
          eventHandler.handleMessageEvent(event as QbftReceivedMessageEvent)
        }

        else -> {
          throw IllegalStateException("Unhandled event type: ${event.type}")
        }
      }
    } catch (e: Exception) {
      log.error("State machine threw exception while processing event \\{$event\\}", e)
    }
    log.debug("handleEvent type={} took {}ms", event.type, (System.nanoTime() - t0) / 1_000_000L)
  }
}
