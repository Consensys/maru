/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft

import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.logging.log4j.LogManager
import org.hyperledger.besu.consensus.common.bft.BftEventQueue
import org.hyperledger.besu.consensus.common.bft.events.BftEvent
import org.hyperledger.besu.consensus.common.bft.events.BftEvents

class QbftEventProcessor(
  private val incomingQueue: BftEventQueue,
  private val eventMultiplexer: QbftEventMultiplexer,
) : Runnable {
  companion object {
    /**
     * Spin window after most events (PREPARE, COMMIT, etc.) — covers the time until the next
     * QBFT message arrives during an active consensus round (~50ms budget).
     */
    private const val DEFAULT_SPIN_NS = 50_000_000L // 50 ms

    /**
     * Spin window after BLOCK_TIMER_EXPIRY — the proposer issues engine_getPayload which can take
     * up to ~150ms, so non-proposers must stay on-CPU to pick up the PROPOSAL promptly.
     */
    private const val TIMER_SPIN_NS = 200_000_000L // 200 ms
  }

  private val log: org.apache.logging.log4j.Logger = LogManager.getLogger(this.javaClass)
  private val shutdownLatch = CountDownLatch(1)

  @Volatile private var shutdown = false

  /** Nanotime of the most recent event handled; 0 = no event yet / outside spin window. */
  @Volatile private var lastEventNs = 0L

  /** Spin window length to use after the last event (set based on event type). */
  @Volatile private var currentSpinWindowNs = DEFAULT_SPIN_NS

  /**
   * Indicate to the processor that it can be started
   */
  @Synchronized
  fun start() {
    shutdown = false
  }

  /**
   * Indicate to the processor that it should gracefully stop at its next opportunity
   */
  @Synchronized
  fun stop() {
    shutdown = true
  }

  /**
   * Await stop.
   *
   * @throws InterruptedException the interrupted exception
   */
  @Throws(InterruptedException::class)
  fun awaitStop() {
    shutdownLatch.await()
  }

  override fun run() {
    try {
      // Start the event queue. Until it is started it won't accept new events from peers
      incomingQueue.start()

      while (!shutdown) {
        val event = nextEvent()
        if (event.isPresent) {
          val e = event.get()
          // Choose spin window: wider after block-timer so non-proposers stay on-CPU
          // while the proposer fetches the payload from the EL.
          currentSpinWindowNs =
            if (e.type == BftEvents.Type.BLOCK_TIMER_EXPIRY) TIMER_SPIN_NS else DEFAULT_SPIN_NS
          lastEventNs = System.nanoTime()
          eventMultiplexer.handleEvent(e)
        }
      }

      incomingQueue.stop()
    } catch (t: Throwable) {
      log.error("BFT Mining thread has suffered a fatal error, mining has been halted", t)
    }

    // Clean up the executor service the round timer has been utilising
    log.info("Shutting down BFT event processor")
    shutdownLatch.countDown()
  }

  private fun nextEvent(): Optional<BftEvent> {
    // If we're within the spin window of the last event, busy-wait on the queue.
    // This keeps the thread on-CPU so it can pick up the next QBFT message with
    // microsecond latency instead of OS reschedule latency (5–400 ms on a loaded JVM).
    val spinDeadlineNs = lastEventNs + currentSpinWindowNs
    if (lastEventNs != 0L && System.nanoTime() < spinDeadlineNs) {
      while (!shutdown && System.nanoTime() < spinDeadlineNs) {
        try {
          val event = incomingQueue.poll(0, TimeUnit.NANOSECONDS)
          if (event != null) return Optional.of(event)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return Optional.empty()
        }
        Thread.onSpinWait()
      }
    }

    // Outside the spin window — park the thread to avoid burning CPU during idle periods
    // (between slots, or when consensus is stalled waiting for a round timer).
    return try {
      Optional.ofNullable(incomingQueue.poll(500, TimeUnit.MILLISECONDS))
    } catch (_: InterruptedException) {
      // If the queue was interrupted propagate it and spin to check our shutdown status
      Thread.currentThread().interrupt()
      Optional.empty()
    }
  }
}
