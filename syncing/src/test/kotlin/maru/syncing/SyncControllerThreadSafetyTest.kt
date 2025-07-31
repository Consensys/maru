/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// These tests employ multiple threads on their own, so they could use some more CPU space than usual
// @Execution(ExecutionMode.SAME_THREAD)
class SyncControllerThreadSafetyTest {
  private lateinit var syncController: BeaconSyncControllerImpl

  private fun createController(
    blockNumber: ULong,
    clSyncService: CLSyncService = FakeCLSyncService(),
  ): BeaconSyncControllerImpl = createSyncController(blockNumber, clSyncService)

  @BeforeEach
  fun setUp() {
    syncController = createController(50UL)
  }

  @Test
  fun `should handle concurrent status updates without race conditions`() {
    val executor = Executors.newFixedThreadPool(3)
    val barrier = CyclicBarrier(3)
    val iterations = 1000

    val clStatusUpdates = mutableListOf<CLSyncStatus>()
    val elStatusUpdates = mutableListOf<ELSyncStatus>()
    val beaconSyncCompletions = AtomicInteger(0)
    val fullSyncCompletions = AtomicInteger(0)

    // Set handlers once during initialization (as per real usage)
    syncController.onClSyncStatusUpdate { synchronized(clStatusUpdates) { clStatusUpdates.add(it) } }
    syncController.onElSyncStatusUpdate { synchronized(elStatusUpdates) { elStatusUpdates.add(it) } }
    syncController.onBeaconSyncComplete { beaconSyncCompletions.incrementAndGet() }
    syncController.onFullSyncComplete { fullSyncCompletions.incrementAndGet() }

    try {
      // Thread 1: Updates CL status
      executor.submit {
        barrier.await()
        repeat(iterations) { i ->
          val status = if (i % 2 == 0) CLSyncStatus.SYNCING else CLSyncStatus.SYNCED
          syncController.updateClSyncStatus(status)
          Thread.sleep(5) // Small delay to encourage race conditions
        }
      }

      // Thread 2: Updates EL status
      executor.submit {
        barrier.await()
        repeat(iterations) { i ->
          val status = if (i % 2 == 0) ELSyncStatus.SYNCING else ELSyncStatus.SYNCED
          syncController.updateElSyncStatus(status)
          Thread.sleep(5)
        }
      }

      // Thread 3: Chain head updates
      executor.submit {
        barrier.await()
        repeat(iterations) { i ->
          val target = 100 + (i % 50)
          syncController.onBeaconChainSyncTargetUpdated(target.toULong())
          Thread.sleep(5)
        }
      }

      executor.shutdown()
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue()

      // Verify that ELSyncStatus is never SYNCED when CL status is SYNCING
      val finalClStatus = syncController.getCLSyncStatus()
      val finalElStatus = syncController.getElSyncStatus()
      val isFullInSync = syncController.isNodeFullInSync()

      assertThat(isFullInSync).isEqualTo(finalClStatus == CLSyncStatus.SYNCED && finalElStatus == ELSyncStatus.SYNCED)
      assertThat(finalElStatus == ELSyncStatus.SYNCED && finalClStatus == CLSyncStatus.SYNCING).isFalse

      // Verify we received status updates (exact count may vary due to concurrency)
      assertThat(clStatusUpdates).size().isGreaterThan(iterations / 8)
      assertThat(elStatusUpdates).size().isGreaterThan(iterations / 8)
    } finally {
      if (!executor.isShutdown) {
        executor.shutdownNow()
      }
    }
  }

  @Test
  fun `should maintain EL-follows-CL invariant under concurrent access`() {
    val executor = Executors.newFixedThreadPool(3)
    val latch = CountDownLatch(3)
    val iterations = 500
    val invariantViolations = AtomicInteger(0)

    // Monitor for invariant violations: when CL starts syncing, EL should never be SYNCED
    executor.submit {
      repeat(iterations * 3) {
        val clStatus = syncController.getCLSyncStatus()
        val elStatus = syncController.getElSyncStatus()

        // This should never happen: CL syncing while EL is synced
        if (clStatus == CLSyncStatus.SYNCING && elStatus == ELSyncStatus.SYNCED) {
          invariantViolations.incrementAndGet()
        }
        Thread.sleep(5)
      }
      latch.countDown()
    }

    try {
      // Thread 1: Rapid CL status changes
      executor.submit {
        repeat(iterations) { i ->
          val status = if (i % 3 == 0) CLSyncStatus.SYNCING else CLSyncStatus.SYNCED
          syncController.updateClSyncStatus(status)
        }
        latch.countDown()
      }

      // Thread 2: Rapid EL status changes
      executor.submit {
        repeat(iterations) { i ->
          val status = if (i % 3 == 0) ELSyncStatus.SYNCING else ELSyncStatus.SYNCED
          syncController.updateElSyncStatus(status)
        }
        latch.countDown()
      }

      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue()

      // The invariant should never be violated
      assertThat(invariantViolations.get()).isEqualTo(0)
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `should handle concurrent handler invocation`() {
    val executor = Executors.newFixedThreadPool(2)
    val iterations = 100
    val handlerCallCounts = AtomicInteger(0)
    val latch = CountDownLatch(2)

    // Set handlers once during initialization
    syncController.onClSyncStatusUpdate { handlerCallCounts.incrementAndGet() }
    syncController.onElSyncStatusUpdate { handlerCallCounts.incrementAndGet() }

    try {
      // Thread 1: Triggers CL status changes
      executor.submit {
        repeat(iterations) { i ->
          syncController.updateClSyncStatus(if (i % 2 == 0) CLSyncStatus.SYNCING else CLSyncStatus.SYNCED)
          Thread.sleep(15)
        }
        latch.countDown()
      }

      // Thread 2: Triggers EL status changes
      executor.submit {
        repeat(iterations) { i ->
          syncController.updateElSyncStatus(if (i % 2 == 0) ELSyncStatus.SYNCING else ELSyncStatus.SYNCED)
          Thread.sleep(15)
        }
        latch.countDown()
      }

      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue()

      // Should have received handler calls without crashes or deadlocks
      assertThat(handlerCallCounts.get()).isGreaterThan(iterations)
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `should handle concurrent sync target updates atomically`() {
    val executor = Executors.newFixedThreadPool(4)
    val iterations = 1000
    val latch = CountDownLatch(4)
    val syncTargetCalls = mutableListOf<ULong>()
    val trackingService =
      object : CLSyncService {
        override fun setSyncTarget(syncTarget: ULong) {
          synchronized(syncTargetCalls) {
            syncTargetCalls.add(syncTarget)
          }
        }

        override fun onSyncComplete(handler: (ULong) -> Unit) {}
      }

    val controller = createController(50UL, trackingService)

    try {
      // Multiple threads calling onChainHeadUpdated with different targets
      repeat(4) { threadIndex ->
        executor.submit {
          repeat(iterations) { i ->
            val target = (threadIndex * 1000 + i).toULong()
            controller.onBeaconChainSyncTargetUpdated(target)
          }
          latch.countDown()
        }
      }

      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue()

      synchronized(syncTargetCalls) {
        // Should have received sync target calls without duplicates for same values
        assertThat(syncTargetCalls).isNotEmpty()

        // No two consecutive calls should have the same value
        val consecutiveDuplicates = syncTargetCalls.zipWithNext().count { (a, b) -> a == b }
        assertThat(consecutiveDuplicates).isEqualTo(0)
      }
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `should maintain consistent state during rapid concurrent transitions`() {
    val executor = Executors.newFixedThreadPool(2)
    val iterations = 1000
    val latch = CountDownLatch(2)
    val stateSnapshots = mutableListOf<SyncState>()

    try {
      // Thread 1: Rapid CL transitions
      executor.submit {
        repeat(iterations) { i ->
          syncController.updateClSyncStatus(if (i % 2 == 0) CLSyncStatus.SYNCING else CLSyncStatus.SYNCED)

          // Capture state snapshot atomically
          val snapshot = syncController.captureStateSnapshot()
          synchronized(stateSnapshots) {
            stateSnapshots.add(snapshot)
          }
        }
        latch.countDown()
      }

      // Thread 2: Rapid EL transitions
      executor.submit {
        repeat(iterations) { i ->
          syncController.updateElSyncStatus(if (i % 2 == 0) ELSyncStatus.SYNCING else ELSyncStatus.SYNCED)

          // Capture state snapshot atomically
          val snapshot = syncController.captureStateSnapshot()
          synchronized(stateSnapshots) {
            stateSnapshots.add(snapshot)
          }
        }
        latch.countDown()
      }

      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue()

      // Verify that EL status is never SYNCED when the CL is SYNCING (business rule invariant)
      synchronized(stateSnapshots) {
        stateSnapshots.forEach { snapshot ->
          assertThat(snapshot.elStatus == ELSyncStatus.SYNCED && snapshot.clStatus == CLSyncStatus.SYNCING)
            .withFailMessage("Found invalid state: CL=${snapshot.clStatus}, EL=${snapshot.elStatus}")
            .isFalse()
        }
      }
    } finally {
      executor.shutdownNow()
    }
  }
}
