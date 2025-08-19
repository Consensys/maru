/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import kotlin.time.Duration.Companion.seconds
import maru.core.ext.DataGenerators
import maru.database.BeaconChain
import maru.database.InMemoryBeaconChain
import maru.p2p.PeersHeadBlockProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testutils.maru.TestablePeriodicTimer

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
  private class TestBeaconSyncTargetUpdateHandler : BeaconSyncTargetUpdateHandler {
    val receivedTargets = mutableListOf<ULong>()

    override fun onBeaconChainSyncTargetUpdated(syncTargetBlockNumber: ULong) {
      receivedTargets.add(syncTargetBlockNumber)
    }

    fun reset() {
      receivedTargets.clear()
    }
  }

  private lateinit var peersHeadsProvider: TestPeersHeadBlockProvider
  private lateinit var syncTargetUpdateHandler: TestBeaconSyncTargetUpdateHandler
  private lateinit var targetChainHeadCalculator: TestSyncTargetSelector
  private lateinit var config: PeerChainTracker.Config
  private lateinit var timer: TestablePeriodicTimer
  private lateinit var peerChainTracker: PeerChainTracker
  private val beaconChain: BeaconChain = InMemoryBeaconChain(DataGenerators.randomBeaconState(7uL))

  @BeforeEach
  fun setUp() {
    peersHeadsProvider = TestPeersHeadBlockProvider()
    syncTargetUpdateHandler = TestBeaconSyncTargetUpdateHandler()
    targetChainHeadCalculator = TestSyncTargetSelector()
    timer = TestablePeriodicTimer()

    config =
      PeerChainTracker.Config(
        pollingUpdateInterval = 1.seconds,
      )

    // Use the lambda to inject our testable timer
    peerChainTracker =
      PeerChainTracker(
        peersHeadsProvider,
        syncTargetUpdateHandler,
        targetChainHeadCalculator,
        config,
        timerFactory = { _, _ -> timer },
        beaconChain = beaconChain,
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
    assertThat(timer.scheduledTask).isNotNull
    assertThat(timer.delay).isEqualTo(0L)
    assertThat(timer.period).isEqualTo(1000L)
  }

  @Test
  fun `stop should cancel timer`() {
    // Arrange
    peerChainTracker.start()

    // Act
    peerChainTracker.stop()

    // Assert
    assertThat(timer.scheduledTask).isNull()
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
    val latestBeaconBlockNumber = beaconChain.getLatestBeaconState().latestBeaconBlockHeader.number
    // Arrange - Start with no peers
    peersHeadsProvider.setPeersHeads(emptyMap())

    // Act
    peerChainTracker.start()
    timer.runNextTask()

    // Assert - When there are no peers it should just set the sync target to 0
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSameElementsAs(listOf(latestBeaconBlockNumber))

    // Arrange - Add peers
    val peersHeads =
      mapOf(
        "peer1" to 100UL,
      )
    peersHeadsProvider.setPeersHeads(peersHeads)

    // Act - Update with peers
    timer.runNextTask()

    // Assert - Update should occur when peers are added
    assertThat(syncTargetUpdateHandler.receivedTargets).hasSameElementsAs(listOf(latestBeaconBlockNumber, 100UL))
  }
}
