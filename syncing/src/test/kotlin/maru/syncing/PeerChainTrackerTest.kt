/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import java.util.Timer
import java.util.TimerTask
import kotlin.time.Duration.Companion.seconds
import maru.p2p.PeersHeadBlockProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PeerChainTrackerTest {
  // Dummy implementation of PeersHeadBlockProvider for testing
  private class TestPeersHeadBlockProvider : PeersHeadBlockProvider {
    private var peersHeads = mutableMapOf<String, ULong>()

    fun setPeersHeads(newPeersHeads: Map<String, ULong>) {
      peersHeads.clear()
      peersHeads.putAll(newPeersHeads)
    }

    override fun getPeersHeads(): Map<String, ULong> = peersHeads.toMap()
  }

  // Dummy implementation of SyncTargetSelector for testing
  private class TestSyncTargetSelector : SyncTargetSelector {
    // Simply returns the maximum value
    override fun selectBestSyncTarget(peerHeads: List<ULong>): ULong = peerHeads.maxOrNull() ?: 0UL
  }

  // Dummy implementation of SyncTargetUpdateHandler for testing
  private class TestSyncTargetUpdateHandler : SyncTargetUpdateHandler {
    val receivedTargets = mutableListOf<ULong>()

    override fun onChainHeadUpdated(beaconBlockNumber: ULong) {
      receivedTargets.add(beaconBlockNumber)
    }

    fun reset() {
      receivedTargets.clear()
    }
  }

  // Test implementation that allows controlling the timer execution
  private class TestableTimer : Timer("test-timer", true) {
    val scheduledTasks = mutableListOf<TimerTask>()
    val delays = mutableListOf<Long>()
    val periods = mutableListOf<Long>()
    var purge: () -> Int = { 0 } // Default implementation returns 0 (not cancelled)

    override fun scheduleAtFixedRate(
      task: TimerTask,
      delay: Long,
      period: Long,
    ) {
      scheduledTasks.add(task)
      delays.add(delay)
      periods.add(period)
    }

    fun runNextTask() {
      if (scheduledTasks.isNotEmpty()) {
        scheduledTasks[0].run()
      }
    }

    // Ensuring that cancel() works properly for cleanup
    override fun cancel() {
      super.cancel()
      scheduledTasks.clear()
      delays.clear()
      periods.clear()
    }

    // Mock implementation for the purge() method
    override fun purge(): Int = purge.invoke()
  }

  private lateinit var peersHeadsProvider: TestPeersHeadBlockProvider
  private lateinit var syncTargetUpdateHandler: TestSyncTargetUpdateHandler
  private lateinit var targetChainHeadCalculator: TestSyncTargetSelector
  private lateinit var config: PeerChainTracker.Config
  private lateinit var timer: TestableTimer
  private lateinit var peerChainTracker: PeerChainTracker

  @BeforeEach
  fun setUp() {
    peersHeadsProvider = TestPeersHeadBlockProvider()
    syncTargetUpdateHandler = TestSyncTargetUpdateHandler()
    targetChainHeadCalculator = TestSyncTargetSelector()
    timer = TestableTimer()

    config =
      PeerChainTracker.Config(
        pollingUpdateInterval = 1.seconds,
        granularity = 10u,
      )

    // Use the lambda to inject our testable timer
    peerChainTracker =
      PeerChainTracker(
        peersHeadsProvider,
        syncTargetUpdateHandler,
        targetChainHeadCalculator,
        config,
        timerFactory = { _, _ -> timer },
      )
  }

  @AfterEach
  fun tearDown() {
    peerChainTracker.stop()
  }

  @Test
  fun `start should schedule timer with correct parameters`() {
    // Act
    peerChainTracker.start()

    // Assert
    assertThat(timer.scheduledTasks).hasSize(1)
    assertThat(timer.delays[0]).isEqualTo(0L)
    assertThat(timer.periods[0]).isEqualTo(1000L)
  }

  @Test
  fun `stop should cancel timer`() {
    // Arrange
    peerChainTracker.start()

    // Act
    peerChainTracker.stop()

    // Assert
    assertThat(timer.scheduledTasks).isEmpty()
  }

  @Test
  fun `should round heights according to granularity`() {
    // Arrange
    val peersHeads =
      mapOf(
        "peer1" to 105UL,
        "peer2" to 110UL,
        "peer3" to 119UL,
      )
    peersHeadsProvider.setPeersHeads(peersHeads)

    // Act
    peerChainTracker.start()
    timer.runNextTask()

    // Assert - since we're using a max value selector,
    // the result should be the max rounded value (110UL)
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSize(1)
    assertThat(syncTargetUpdateHandler.receivedTargets[0]).isEqualTo(110UL)
  }

  @Test
  fun `should not notify if no changes in rounded heights`() {
    // Arrange - Initial state
    val initialPeersHeads =
      mapOf(
        "peer1" to 100UL,
        "peer2" to 110UL,
      )
    peersHeadsProvider.setPeersHeads(initialPeersHeads)

    // Act - First update
    peerChainTracker.start()
    timer.runNextTask()

    // Wait for first update and reset for next test
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSize(1)
    syncTargetUpdateHandler.reset()

    // Arrange - Subsequent state with small changes that don't affect rounded values
    val subsequentPeersHeads =
      mapOf(
        "peer1" to 101UL,
        "peer2" to 111UL,
      )
    peersHeadsProvider.setPeersHeads(subsequentPeersHeads)

    // Act - Second update
    timer.runNextTask()

    // Assert - No new updates should have occurred
    assertThat(syncTargetUpdateHandler.receivedTargets).isEmpty()
    syncTargetUpdateHandler.reset()

    // Arrange - Subsequent state with significant advancement by peers
    val progressedPeersHeads =
      mapOf(
        "peer1" to 111UL,
        "peer2" to 122UL,
      )
    peersHeadsProvider.setPeersHeads(progressedPeersHeads)

    // Act - Third update
    timer.runNextTask()

    // Assert - New target should be received
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSize(1)
    assertThat(syncTargetUpdateHandler.receivedTargets[0]).isEqualTo(120UL)
  }

  @Test
  fun `should detect peer disconnection and update target`() {
    // Arrange - Initial state with two peers
    val initialPeersHeads =
      mapOf(
        "peer1" to 100UL,
        "peer2" to 200UL,
      )
    peersHeadsProvider.setPeersHeads(initialPeersHeads)

    // Act - First update
    peerChainTracker.start()
    timer.runNextTask()

    // Wait for first update and reset for next test
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSize(1)
    assertThat(syncTargetUpdateHandler.receivedTargets[0]).isEqualTo(200UL)
    syncTargetUpdateHandler.reset()

    // Arrange - Peer disconnection
    val subsequentPeersHeads =
      mapOf(
        "peer1" to 100UL,
      )
    peersHeadsProvider.setPeersHeads(subsequentPeersHeads)

    // Act - Second update after peer disconnection
    timer.runNextTask()

    // Assert - Target should be updated to reflect remaining peer. Sync target can move backwards
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSize(1)
    assertThat(syncTargetUpdateHandler.receivedTargets[0]).isEqualTo(100UL)
  }

  @Test
  fun `should detect new peer connection and update target`() {
    // Arrange - Initial state with one peer
    val initialPeersHeads =
      mapOf(
        "peer1" to 100UL,
      )
    peersHeadsProvider.setPeersHeads(initialPeersHeads)

    // Act - First update
    peerChainTracker.start()
    timer.runNextTask()

    // Wait for first update and reset for next test
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSize(1)
    assertThat(syncTargetUpdateHandler.receivedTargets[0]).isEqualTo(100UL)
    syncTargetUpdateHandler.reset()

    // Arrange - New peer connection
    val subsequentPeersHeads =
      mapOf(
        "peer1" to 100UL,
        "peer2" to 200UL,
      )
    peersHeadsProvider.setPeersHeads(subsequentPeersHeads)

    // Act - Second update after new peer connection
    timer.runNextTask()

    // Assert - Target should be updated to reflect new peer with higher height
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSize(1)
    assertThat(syncTargetUpdateHandler.receivedTargets[0]).isEqualTo(200UL)
  }

  @Test
  fun `should not notify for the same target value twice`() {
    // Arrange - Initial state
    val initialPeersHeads =
      mapOf(
        "peer1" to 100UL,
        "peer2" to 110UL,
      )
    peersHeadsProvider.setPeersHeads(initialPeersHeads)

    // Act - First update
    peerChainTracker.start()
    timer.runNextTask()

    // Wait for first update and reset for next test
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSize(1)
    assertThat(syncTargetUpdateHandler.receivedTargets[0]).isEqualTo(110UL)
    syncTargetUpdateHandler.reset()

    // Arrange - Subsequent state with changed heights but same max
    val subsequentPeersHeads =
      mapOf(
        "peer1" to 100UL,
        "peer2" to 115UL, // Different height but same rounded value (110)
        "peer3" to 105UL, // New peer but with height that rounds to 100
      )
    peersHeadsProvider.setPeersHeads(subsequentPeersHeads)

    // Act - Second update
    timer.runNextTask()

    // Assert - No new target updates despite changes in peer composition
    assertThat(syncTargetUpdateHandler.receivedTargets).isEmpty()
  }

  @Test
  fun `should handle empty peer list`() {
    // Arrange - Start with no peers
    peersHeadsProvider.setPeersHeads(emptyMap())

    // Act
    peerChainTracker.start()
    timer.runNextTask()

    // Assert - No updates should occur with empty peer list
    assertThat(syncTargetUpdateHandler.receivedTargets).isEmpty()

    // Arrange - Add peers
    val peersHeads =
      mapOf(
        "peer1" to 100UL,
      )
    peersHeadsProvider.setPeersHeads(peersHeads)

    // Act - Update with peers
    timer.runNextTask()

    // Assert - Update should occur when peers are added
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSize(1)
    assertThat(syncTargetUpdateHandler.receivedTargets[0]).isEqualTo(100UL)
  }

  @Test
  fun `start should be idempotent`() {
    // Act - Call start twice
    peerChainTracker.start()
    peerChainTracker.start() // Should be a no-op

    // Assert - Only one task should be scheduled
    assertThat(timer.scheduledTasks).hasSize(1)
  }

  @Test
  fun `stop should be idempotent`() {
    // Arrange - Start the tracker
    peerChainTracker.start()
    assertThat(timer.scheduledTasks).hasSize(1)

    // Act - Call stop twice
    peerChainTracker.stop()
    peerChainTracker.stop() // Should be a no-op

    // Assert - Scheduled tasks should be cleared
    assertThat(timer.scheduledTasks).isEmpty()
  }

  @Test
  fun `should be able to restart after stopping`() {
    // Arrange - Set up initial peer data
    val initialPeersHeads = mapOf("peer1" to 100UL)
    peersHeadsProvider.setPeersHeads(initialPeersHeads)

    // Start the tracker and verify initial sync target
    peerChainTracker.start()
    timer.runNextTask()
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSize(1)
    assertThat(syncTargetUpdateHandler.receivedTargets[0]).isEqualTo(100UL)

    peerChainTracker.stop()
    syncTargetUpdateHandler.reset()

    val updatedPeersHeads = mapOf("peer1" to 100UL, "peer2" to 200UL)
    peersHeadsProvider.setPeersHeads(updatedPeersHeads)

    timer.purge = { -1 } // Mock the purge method to simulate a cancelled timer

    peerChainTracker.start()

    // Verify that no updates are sent before timer task runs
    assertThat(syncTargetUpdateHandler.receivedTargets).isEmpty()

    timer.runNextTask()

    // Verify that after restart, the tracker properly processes the updated peer data
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSize(1)
    assertThat(syncTargetUpdateHandler.receivedTargets[0]).isEqualTo(200UL)

    // Further verify the functionality by updating peers again
    syncTargetUpdateHandler.reset()
    val finalPeersHeads = mapOf("peer1" to 100UL, "peer2" to 300UL)
    peersHeadsProvider.setPeersHeads(finalPeersHeads)

    timer.runNextTask()

    // Verify that the tracker continues to function properly after restart
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSize(1)
    assertThat(syncTargetUpdateHandler.receivedTargets[0]).isEqualTo(300UL)
  }
}
