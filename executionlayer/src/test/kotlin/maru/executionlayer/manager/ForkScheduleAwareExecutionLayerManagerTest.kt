/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.executionlayer.manager

import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import maru.config.consensus.ElFork
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import net.consensys.FakeFixedClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ForkScheduleAwareExecutionLayerManagerTest {
  val elManagerMap = ElFork.entries.associateWith { mock<ExecutionLayerManager>() }

  @Test
  fun `throws when missing el manager`() {
    val exception =
      assertThrows<IllegalArgumentException> {
        ForkScheduleAwareExecutionLayerManager(
          forksSchedule = mock<ForksSchedule>(),
          executionLayerManagerMap = emptyMap(),
        )
      }
    assertThat(exception).hasMessageContaining("No execution layer manager provided")
  }

  @Test
  fun `returns first elFork when empty fork schedule`() {
    val forksSchedule = ForksSchedule(1337u, emptyList())
    val forkScheduleAwareManager =
      ForkScheduleAwareExecutionLayerManager(
        forksSchedule = forksSchedule,
        executionLayerManagerMap = elManagerMap,
      )
    assertThat(forkScheduleAwareManager.getCurrentElFork()).isEqualTo(ElFork.entries.first())
  }

  @Test
  fun `returns elFork from fork schedule`() {
    val forksSchedule =
      ForksSchedule(
        1337u,
        listOf(
          ForkSpec(
            1L,
            1,
            mock<QbftConsensusConfig> {
              whenever(it.elFork).thenReturn(ElFork.Shanghai)
            },
          ),
          ForkSpec(
            5L,
            1,
            mock<QbftConsensusConfig> {
              whenever(it.elFork).thenReturn(ElFork.Prague)
            },
          ),
        ),
      )
    val fakeClock = FakeFixedClock(Instant.fromEpochSeconds(0L))
    val forkScheduleAwareManager =
      ForkScheduleAwareExecutionLayerManager(
        forksSchedule = forksSchedule,
        executionLayerManagerMap = elManagerMap,
        clock = fakeClock,
      )
    // timestamp 0
    assertThat(forkScheduleAwareManager.getCurrentElFork()).isEqualTo(ElFork.entries.first())
    fakeClock.advanceBy(1.seconds)
    // timestamp 1
    assertThat(forkScheduleAwareManager.getCurrentElFork()).isEqualTo(ElFork.Shanghai)
    fakeClock.advanceBy(1.seconds)
    // timestamp 2
    assertThat(forkScheduleAwareManager.getCurrentElFork()).isEqualTo(ElFork.Shanghai)
    fakeClock.advanceBy(3.seconds)
    // timestamp 5
    assertThat(forkScheduleAwareManager.getCurrentElFork()).isEqualTo(ElFork.Prague)
    fakeClock.advanceBy(1.seconds)
    // timestamp 6
    assertThat(forkScheduleAwareManager.getCurrentElFork()).isEqualTo(ElFork.Prague)
  }
}
