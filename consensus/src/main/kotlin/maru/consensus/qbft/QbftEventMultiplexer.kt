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
   * dispatched to the QBFT state machine. Parameters: (blockNumber).
   * Intended for benchmarking/testing — the callback implementer captures timestamps themselves.
   */
  var onBlockTimerFired: ((blockNumber: Long) -> Unit)? = null

  /**
   * Optional observer called before every event is dispatched on the event loop thread.
   * Parameters: (eventLabel).
   * eventLabel encodes the event type and, for MESSAGE events, the QBFT message code:
   * - "BLOCK_TIMER_EXPIRY", "NEW_CHAIN_HEAD", "ROUND_EXPIRY"
   * - "MESSAGE_PROPOSAL", "MESSAGE_PREPARE", "MESSAGE_COMMIT", "MESSAGE_ROUND_CHANGE"
   */
  var onBeforeEvent: ((eventLabel: String) -> Unit)? = null

  /**
   * Optional observer called after every event is processed on the event loop thread.
   * Parameters: (eventLabel).
   * Fires in both success and error paths.
   */
  var onAfterEvent: ((eventLabel: String) -> Unit)? = null

  fun handleEvent(event: BftEvent) {
    var eventLabel = event.type.name
    // Compute eventLabel for MESSAGE events before invoking onBeforeEvent
    if (event.type == BftEvents.Type.MESSAGE) {
      val msgEvent = event as QbftReceivedMessageEvent
      eventLabel = "MESSAGE_${messageCodeName(msgEvent.message.data.code)}"
    }
    onBeforeEvent?.invoke(eventLabel)
    try {
      log.trace("Received event type: {}, event: {},", event.type, event)
      when (event.type) {
        BftEvents.Type.ROUND_EXPIRY -> {
          eventHandler.handleRoundExpiry(event as RoundExpiry)
        }

        BftEvents.Type.NEW_CHAIN_HEAD -> {
          eventHandler.handleNewBlockEvent(event as QbftNewChainHead)
        }

        BftEvents.Type.BLOCK_TIMER_EXPIRY -> {
          val timerExpiry = event as BlockTimerExpiry
          onBlockTimerFired?.invoke(timerExpiry.roundIdentifier.sequenceNumber)
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
    onAfterEvent?.invoke(eventLabel)
  }

  private fun messageCodeName(code: Int): String =
    when (code) {
      0x12 -> "PROPOSAL"
      0x13 -> "PREPARE"
      0x14 -> "COMMIT"
      0x15 -> "ROUND_CHANGE"
      else -> "UNKNOWN(0x${code.toString(16)})"
    }
}
