/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.time.Duration.Companion.seconds
import maru.core.Protocol
import maru.syncing.SyncStatusProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import testutils.maru.TestablePeriodicTimer

class ProtocolStarterTest {
  private class StubProtocol : Protocol {
    var started = false

    override fun start() {
      started = true
    }

    override fun stop() {
      started = false
    }
  }

  private val chainId = 1337u

  private val protocol1 = StubProtocol()
  private val protocol2 = StubProtocol()

  private val protocolConfig1 = object : ConsensusConfig {}
  private val forkSpec1 = ForkSpec(0, 1, protocolConfig1)
  private val protocolConfig2 = object : ConsensusConfig {}
  private val forkSpec2 =
    ForkSpec(
      15,
      2,
      protocolConfig2,
    )

  private val mockSyncStatusProvider = mock<SyncStatusProvider>()

  @BeforeEach
  fun stopStubProtocols() {
    protocol1.stop()
    protocol2.stop()
  }

  @Test
  fun `ProtocolStarter kickstarts the protocol based on current time`() {
    val forksSchedule =
      ForksSchedule(
        chainId,
        listOf(
          forkSpec1,
          forkSpec2,
        ),
      )
    val protocolStarter =
      createProtocolStarter(
        forksSchedule = forksSchedule,
        clockMilliseconds = 16000, // After fork transition at 15
      )
    protocolStarter.start()
    val currentProtocolWithConfig = protocolStarter.currentProtocolWithForkReference.get()
    assertThat(currentProtocolWithConfig.fork).isEqualTo(forkSpec2)
    assertThat(protocol2.started).isTrue()
    assertThat(protocol1.started).isFalse()
  }

  @Test
  fun `ProtocolStarter uses first fork when current time is before any fork transition`() {
    val forksSchedule =
      ForksSchedule(
        chainId,
        listOf(
          forkSpec1,
          forkSpec2,
        ),
      )
    val protocolStarter =
      createProtocolStarter(
        forksSchedule = forksSchedule,
        clockMilliseconds = 5000, // Before fork transition at 15
      )
    protocolStarter.start()
    val currentProtocolWithConfig = protocolStarter.currentProtocolWithForkReference.get()
    assertThat(currentProtocolWithConfig.fork).isEqualTo(forkSpec1)
    assertThat(protocol1.started).isTrue()
    assertThat(protocol2.started).isFalse()
  }

  @Test
  fun `ProtocolStarter waits for fork transition time before switching`() {
    val forksSchedule =
      ForksSchedule(
        chainId,
        listOf(
          forkSpec1,
          forkSpec2,
        ),
      )
    val protocolStarter =
      createProtocolStarter(
        forksSchedule = forksSchedule,
        clockMilliseconds = 10000, // 5 seconds before fork transition at 15
      )
    protocolStarter.start()

    // Should start with first fork since transition time hasn't arrived yet
    val currentProtocolWithConfig = protocolStarter.currentProtocolWithForkReference.get()
    assertThat(currentProtocolWithConfig.fork).isEqualTo(forkSpec1)
    assertThat(protocol1.started).isTrue()
    assertThat(protocol2.started).isFalse()
  }

  @Test
  fun `ProtocolStarter transitions exactly at fork time`() {
    val forksSchedule =
      ForksSchedule(
        chainId,
        listOf(
          forkSpec1,
          forkSpec2,
        ),
      )
    val protocolStarter =
      createProtocolStarter(
        forksSchedule = forksSchedule,
        clockMilliseconds = 15000, // Exactly at fork transition
      )
    protocolStarter.start()

    val currentProtocolWithConfig = protocolStarter.currentProtocolWithForkReference.get()
    assertThat(currentProtocolWithConfig.fork).isEqualTo(forkSpec2)
    assertThat(protocol1.started).isFalse()
    assertThat(protocol2.started).isTrue()
  }

  @Test
  fun `ProtocolStarter schedules periodic timer correctly`() {
    val timer = TestablePeriodicTimer()
    val forksSchedule =
      ForksSchedule(
        chainId,
        listOf(forkSpec1, forkSpec2),
      )
    val protocolStarter =
      createProtocolStarter(
        forksSchedule = forksSchedule,
        clockMilliseconds = 5000,
        timer = timer,
      )

    protocolStarter.start()

    // Verify timer was scheduled with correct parameters
    assertThat(timer.scheduledTask).isNotNull()
    assertThat(timer.period).isEqualTo(1000L) // 1 second interval
  }

  @Test
  fun `ProtocolStarter switches protocols during periodic polling`() {
    val timer = TestablePeriodicTimer()
    val forksSchedule =
      ForksSchedule(
        chainId,
        listOf(forkSpec1, forkSpec2),
      )

    // Start with a mutable clock that we can advance
    var currentTimeMillis = 10000L // Before fork transition at 15
    val protocolStarter =
      createProtocolStarter(
        forksSchedule = forksSchedule,
        clockMilliseconds = currentTimeMillis,
        timer = timer,
        mutableClock = true,
      ) { currentTimeMillis }

    protocolStarter.start()

    // Initially should be on first protocol
    val initialProtocol = protocolStarter.currentProtocolWithForkReference.get()
    assertThat(initialProtocol.fork).isEqualTo(forkSpec1)
    assertThat(protocol1.started).isTrue()
    assertThat(protocol2.started).isFalse()

    // Advance time to after fork transition and run periodic task
    currentTimeMillis = 16000L // After fork transition at 15
    timer.runNextTask()

    // Should switch to second protocol
    val switchedProtocol = protocolStarter.currentProtocolWithForkReference.get()
    assertThat(switchedProtocol.fork).isEqualTo(forkSpec2)
    assertThat(protocol1.started).isFalse() // Previous protocol should be stopped
    assertThat(protocol2.started).isTrue()
  }

  @Test
  fun `ProtocolStarter does not switch if time has not reached fork transition`() {
    val timer = TestablePeriodicTimer()
    val forksSchedule =
      ForksSchedule(
        chainId,
        listOf(forkSpec1, forkSpec2),
      )

    var currentTimeMillis = 10000L // Before fork transition at 15
    val protocolStarter =
      createProtocolStarter(
        forksSchedule = forksSchedule,
        clockMilliseconds = currentTimeMillis,
        timer = timer,
        mutableClock = true,
      ) { currentTimeMillis }

    protocolStarter.start()

    // Initially should be on first protocol
    assertThat(protocolStarter.currentProtocolWithForkReference.get().fork).isEqualTo(forkSpec1)
    assertThat(protocol1.started).isTrue()

    // Advance time but not enough to trigger fork transition
    currentTimeMillis = 14000L // Still before fork transition at 15
    timer.runNextTask()

    // Should remain on first protocol
    assertThat(protocolStarter.currentProtocolWithForkReference.get().fork).isEqualTo(forkSpec1)
    assertThat(protocol1.started).isTrue()
    assertThat(protocol2.started).isFalse()
  }

  @Test
  fun `ProtocolStarter stops periodic timer when stopped`() {
    val timer = TestablePeriodicTimer()
    val forksSchedule =
      ForksSchedule(
        chainId,
        listOf(forkSpec1, forkSpec2),
      )
    val protocolStarter =
      createProtocolStarter(
        forksSchedule = forksSchedule,
        clockMilliseconds = 5000,
        timer = timer,
      )

    protocolStarter.start()
    assertThat(timer.scheduledTask).isNotNull()

    protocolStarter.stop()
    assertThat(timer.scheduledTask).isNull()
  }

  private val protocolFactory =
    object : ProtocolFactory {
      override fun create(forkSpec: ForkSpec): Protocol =
        when (forkSpec.configuration) {
          protocolConfig1 -> protocol1
          protocolConfig2 -> protocol2
          else -> error("invalid protocol config")
        }
    }

  private fun createProtocolStarter(
    forksSchedule: ForksSchedule,
    clockMilliseconds: Long,
    timer: TestablePeriodicTimer = TestablePeriodicTimer(),
    mutableClock: Boolean = false,
    timeProvider: (() -> Long)? = null,
  ): ProtocolStarter {
    val clock =
      if (mutableClock && timeProvider != null) {
        object : Clock() {
          override fun getZone() = ZoneOffset.UTC

          override fun instant() = Instant.ofEpochMilli(timeProvider())

          override fun withZone(zone: java.time.ZoneId?) = this
        }
      } else {
        Clock.fixed(Instant.ofEpochMilli(clockMilliseconds), ZoneOffset.UTC)
      }

    return ProtocolStarter.create(
      forksSchedule = forksSchedule,
      protocolFactory = protocolFactory,
      nextBlockTimestampProvider =
        NextBlockTimestampProviderImpl(
          clock = clock,
          forksSchedule = forksSchedule,
        ),
      syncStatusProvider = mockSyncStatusProvider,
      forkTransitionCheckInterval = 1.seconds,
      clock = clock,
      timerFactory = { _, _ -> timer },
    )
  }

  fun randomBlockMetadata(timestamp: Long): ElBlockMetadata =
    ElBlockMetadata(
      Random.nextULong(),
      blockHash = Random.nextBytes(32),
      unixTimestampSeconds = timestamp,
    )
}
