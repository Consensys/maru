/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ForksScheduleTest {
  private val consensusConfig = object : ConsensusConfig {}
  private val qbftConsensusConfig = object : ConsensusConfig {}
  private val otherConsensusConfig = object : ConsensusConfig {}
  private val expectedChainId = 1337u

  @Test
  fun `test getForkByTimestamp returns correct fork`() {
    val fork1 = ForkSpec(1L, 1, consensusConfig)
    val fork2 = ForkSpec(2L, 2, consensusConfig)
    val fork3 = ForkSpec(3L, 3, consensusConfig)
    val forks = listOf(fork1, fork2, fork3)

    val schedule = ForksSchedule(expectedChainId, forks)

    assertThat(schedule.getForkByTimestamp(1L)).isEqualTo(fork1)
    assertThat(schedule.getForkByTimestamp(2L)).isEqualTo(fork2)
    assertThat(schedule.getForkByTimestamp(3L)).isEqualTo(fork3)
  }

  @Test
  fun `getForkByTimestamp throws if timestamp is before all forks`() {
    val fork1 = ForkSpec(1000L, 10, consensusConfig)
    val fork2 = ForkSpec(2000L, 20, consensusConfig)
    val forks = listOf(fork1, fork2)

    val schedule = ForksSchedule(expectedChainId, forks)

    val exception =
      assertThrows<IllegalArgumentException> {
        schedule.getForkByTimestamp(500L)
      }
    assertThat(exception).hasMessageContaining("No fork found")
  }

  @Test
  fun `ForkSpec initialization with invalid blockTimeSeconds`() {
    val exception =
      assertThrows<IllegalArgumentException> {
        ForkSpec(1000L, 0, consensusConfig)
      }
    assertThat(exception).hasMessage("blockTimeSeconds must be greater or equal to 1 second")
  }

  @Test
  fun equality() {
    val fork1 = ForkSpec(1000L, 10, consensusConfig)
    val fork2 = ForkSpec(2000L, 20, consensusConfig)
    val forks1 = listOf(fork1, fork2)
    val forks2 = listOf(fork1, fork2)

    val schedule1 = ForksSchedule(expectedChainId, forks1)
    val schedule2 = ForksSchedule(expectedChainId, forks2)

    assertThat(schedule1).isEqualTo(schedule2)
    assertThat(schedule1.hashCode()).isEqualTo(schedule2.hashCode())
  }

  @Test
  fun inequality() {
    val fork1 = ForkSpec(1000L, 10, consensusConfig)
    val fork2 = ForkSpec(2000L, 20, consensusConfig)
    val fork3 = ForkSpec(3000L, 30, consensusConfig)
    val forks1 = listOf(fork1, fork2)
    val forks2 = listOf(fork1, fork3)

    val schedule1 = ForksSchedule(expectedChainId, forks1)
    val schedule2 = ForksSchedule(expectedChainId, forks2)

    assertThat(schedule1).isNotEqualTo(schedule2)
    assertThat(schedule1.hashCode()).isNotEqualTo(schedule2.hashCode())
  }

  @Test
  fun `getForkByConfigType returns fork for matching config class`() {
    val qbftFork = ForkSpec(1000L, 10, qbftConsensusConfig)
    val otherFork = ForkSpec(2000L, 20, otherConsensusConfig)
    val forks = listOf(qbftFork, otherFork)

    val schedule = ForksSchedule(expectedChainId, forks)

    assertThat(schedule.getForkByConfigType(qbftConsensusConfig::class)).isEqualTo(qbftFork)
    assertThat(schedule.getForkByConfigType(otherConsensusConfig::class)).isEqualTo(otherFork)
  }

  @Test
  fun `getForkByConfigType throws exception when config class not found`() {
    val qbftFork = ForkSpec(1000L, 10, qbftConsensusConfig)
    val forks = listOf(qbftFork)

    val schedule = ForksSchedule(expectedChainId, forks)

    val exception =
      assertThrows<IllegalArgumentException> {
        schedule.getForkByConfigType(otherConsensusConfig::class)
      }
    assertThat(exception).hasMessageContaining("No fork found for config type")
  }

  @Test
  fun `getForkByConfigType returns first matching fork when multiple forks exist`() {
    val qbftFork1 = ForkSpec(1000L, 10, qbftConsensusConfig)
    val qbftFork2 = ForkSpec(2000L, 20, qbftConsensusConfig)
    val forks = listOf(qbftFork1, qbftFork2)

    val schedule = ForksSchedule(expectedChainId, forks)

    // Should return the first one found (note: forks are sorted by timestamp in reverse order internally)
    val result = schedule.getForkByConfigType(qbftConsensusConfig::class)
    assertThat(result).isIn(qbftFork1, qbftFork2)
  }
}
