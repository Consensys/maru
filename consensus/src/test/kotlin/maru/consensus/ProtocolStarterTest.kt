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
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.time.Duration.Companion.seconds
import maru.consensus.ForkSpec
import maru.core.Protocol
import maru.subscription.InOrderFanoutSubscriptionManager
import maru.subscription.SubscriptionNotifier
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
  private val forkSpec1 = ForkSpec(0, 5, protocolConfig1)
  private val protocolConfig2 = object : ConsensusConfig {}
  private val forkSpec2 = ForkSpec(15, 2, protocolConfig2)

  private val mockSyncStatusProvider = mock<SyncStatusProvider>()

  val forkTransitions = mutableListOf<ForkSpec>()
  val forkTransitionNotifier =
    InOrderFanoutSubscriptionManager<ForkSpec>().also {
      it.addSyncSubscriber(forkTransitions::add)
    }

  @BeforeEach
  fun stopStubProtocols() {
    forkTransitions.clear()
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
        forkTransitionNotifier = forkTransitionNotifier,
      )
    protocolStarter.start()
    assertActiveProtocol2(protocolStarter)
    assertThat(forkTransitions.size).isEqualTo(1)
    assertThat(forkTransitions.first()).isEqualTo(forkSpec2)
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
        forkTransitionNotifier = forkTransitionNotifier,
      )
    protocolStarter.start()
    assertActiveProtocol1(protocolStarter)
    assertThat(forkTransitions.size).isEqualTo(1)
    assertThat(forkTransitions.first()).isEqualTo(forkSpec1)
  }

  @Test
  fun `ProtocolStarter switches if the next block timestamp past the next fork`() {
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
        forkTransitionNotifier = forkTransitionNotifier,
      )
    protocolStarter.start()

    assertActiveProtocol2(protocolStarter)
    assertThat(forkTransitions.size).isEqualTo(1)
    assertThat(forkTransitions.first()).isEqualTo(forkSpec2)
  }

  @Test
  fun `ProtocolStarter doesn't switch if it's too early`() {
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
        clockMilliseconds = 5000, // Before fork transition at 15, next block still in forkSpec1 period
        forkTransitionNotifier = forkTransitionNotifier,
      )
    protocolStarter.start()

    assertActiveProtocol1(protocolStarter)
    assertThat(forkTransitions.size).isEqualTo(1)
    assertThat(forkTransitions.first()).isEqualTo(forkSpec1)
  }

  @Test
  fun `ProtocolStarter switches protocols during periodic polling`() {
    val timer = TestablePeriodicTimer()
    val forksSchedule =
      ForksSchedule(
        chainId,
        listOf(forkSpec1, forkSpec2),
      )

    var currentTimeMillis = 8000L // Next block at ~13 seconds (before fork at 15)
    val protocolStarter =
      createProtocolStarter(
        forksSchedule = forksSchedule,
        clockMilliseconds = currentTimeMillis,
        timer = timer,
        forkTransitionNotifier = forkTransitionNotifier,
      ) { currentTimeMillis }

    protocolStarter.start()

    // Initially should be on first protocol since next block is before fork transition
    assertActiveProtocol1(protocolStarter)

    currentTimeMillis = 12000L // Next block at ~17 seconds (after fork at 15)
    timer.runNextTask()

    // Should switch to second protocol since next block needs forkSpec2
    assertActiveProtocol2(protocolStarter)

    assertThat(forkTransitions.size).isEqualTo(2)
    assertThat(forkTransitions.first()).isEqualTo(forkSpec1)
    assertThat(forkTransitions.last()).isEqualTo(forkSpec2)
  }

  @Test
  fun `ProtocolStarter does not switch if next block still uses same protocol`() {
    val timer = TestablePeriodicTimer()
    val forksSchedule =
      ForksSchedule(
        chainId,
        listOf(forkSpec1, forkSpec2),
      )

    var currentTimeMillis = 8000L // Next block at ~13 seconds (before fork at 15)
    val protocolStarter =
      createProtocolStarter(
        forksSchedule = forksSchedule,
        clockMilliseconds = currentTimeMillis,
        timer = timer,
        forkTransitionNotifier = forkTransitionNotifier,
      ) { currentTimeMillis }

    protocolStarter.start()

    assertActiveProtocol1(protocolStarter)

    currentTimeMillis = 9000L // Next block at ~14 seconds (still before fork at 15)
    timer.runNextTask()

    assertActiveProtocol1(protocolStarter)
    assertThat(forkTransitions.size).isEqualTo(1)
    assertThat(forkTransitions.first()).isEqualTo(forkSpec1)
  }

  @Test
  fun `ProtocolStarter switches when next block will be produced by new protocol at startup`() {
    val timer = TestablePeriodicTimer()
    val forksSchedule =
      ForksSchedule(
        chainId,
        listOf(forkSpec1, forkSpec2),
      )

    var currentTimeMillis = 11000L // With 5-second block time, next block at ~16 seconds (after fork at 15)
    val protocolStarter =
      createProtocolStarter(
        forksSchedule = forksSchedule,
        clockMilliseconds = currentTimeMillis,
        timer = timer,
        forkTransitionNotifier = forkTransitionNotifier,
      ) { currentTimeMillis }

    protocolStarter.start()

    assertActiveProtocol2(protocolStarter)

    currentTimeMillis = 15000L // Exactly at fork transition
    timer.runNextTask()

    assertActiveProtocol2(protocolStarter)
    assertThat(forkTransitions.size).isEqualTo(1)
    assertThat(forkTransitions.first()).isEqualTo(forkSpec2)
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
    forkTransitionNotifier: SubscriptionNotifier<ForkSpec>,
    timeProvider: (() -> Long)? = null,
  ): ProtocolStarter {
    val clock =
      if (timeProvider != null) {
        object : Clock() {
          override fun getZone() = ZoneOffset.UTC

          override fun instant() = Instant.ofEpochMilli(timeProvider())

          override fun withZone(zone: ZoneId?) = this
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
      forkTransitionNotifier = forkTransitionNotifier,
    )
  }

  private fun assertActiveProtocol1(protocolStarter: ProtocolStarter) {
    val currentProtocol = protocolStarter.currentProtocolWithForkReference.get()
    assertThat(currentProtocol.fork).isEqualTo(forkSpec1)
    assertThat(protocol1.started).isTrue()
    assertThat(protocol2.started).isFalse()
  }

  private fun assertActiveProtocol2(protocolStarter: ProtocolStarter) {
    val currentProtocol = protocolStarter.currentProtocolWithForkReference.get()
    assertThat(currentProtocol.fork).isEqualTo(forkSpec2)
    assertThat(protocol2.started).isTrue()
    assertThat(protocol1.started).isFalse()
  }

  fun randomBlockMetadata(timestamp: Long): ElBlockMetadata =
    ElBlockMetadata(
      Random.nextULong(),
      blockHash = Random.nextBytes(32),
      unixTimestampSeconds = timestamp,
    )
}
