/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import maru.consensus.ChainFork
import maru.consensus.ClFork
import maru.consensus.DifficultyAwareQbftConfig
import maru.consensus.ElFork
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import maru.consensus.QbftConsensusConfig
import maru.core.Validator
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MaruAppFactoryCheckEthApiTest {
  private fun v(): Validator = Validator(ByteArray(20) { 0 })

  private fun forksScheduleWithFutureDifficultyAware(nowTs: ULong): ForksSchedule {
    val pre =
      ForkSpec(
        timestampSeconds = nowTs,
        blockTimeSeconds = 1u,
        configuration =
          QbftConsensusConfig(
            setOf(v()),
            ChainFork(ClFork.QBFT_PHASE0, ElFork.Prague),
          ),
      )
    val future =
      ForkSpec(
        timestampSeconds = nowTs + 10u,
        blockTimeSeconds = 1u,
        configuration =
          DifficultyAwareQbftConfig(
            postTtdConfig =
              QbftConsensusConfig(
                setOf(v()),
                ChainFork(ClFork.QBFT_PHASE0, ElFork.Prague),
              ),
            terminalTotalDifficulty = 42u,
          ),
      )
    return ForksSchedule(chainId = 1337u, forks = listOf(pre, future))
  }

  private fun forksScheduleWithoutFutureDifficultyAware(nowTs: ULong): ForksSchedule {
    val pre =
      ForkSpec(
        timestampSeconds = nowTs,
        blockTimeSeconds = 1u,
        configuration =
          QbftConsensusConfig(
            setOf(v()),
            ChainFork(ClFork.QBFT_PHASE0, ElFork.Prague),
          ),
      )
    val future =
      ForkSpec(
        timestampSeconds = nowTs + 10u,
        blockTimeSeconds = 1u,
        configuration =
          QbftConsensusConfig(
            setOf(v()),
            ChainFork(ClFork.QBFT_PHASE0, ElFork.Prague),
          ),
      )
    return ForksSchedule(chainId = 1337u, forks = listOf(pre, future))
  }

  @Test
  fun `throws when future DifficultyAwareQbft exists and l2EthWeb3j is null`() {
    val nowTs = 1_000_000UL
    val schedule = forksScheduleWithFutureDifficultyAware(nowTs)
    val fixedClock = Clock.fixed(Instant.ofEpochSecond(nowTs.toLong()), ZoneOffset.UTC)

    assertThatThrownBy {
      MaruAppFactory().checkL2EthApiEndpointAndForks(fixedClock, schedule, null)
    }.isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("future fork enables DifficultyAwareQbft")
  }

  @Test
  fun `does not throw when future DifficultyAwareQbft exists and l2EthWeb3j is provided`() {
    val nowTs = 1_000_000UL
    val schedule = forksScheduleWithFutureDifficultyAware(nowTs)
    val fixedClock = Clock.fixed(Instant.ofEpochSecond(nowTs.toLong()), ZoneOffset.UTC)

    // any non-null object
    MaruAppFactory().checkL2EthApiEndpointAndForks(fixedClock, schedule, Any())
  }

  @Test
  fun `does not throw when no future DifficultyAwareQbft exists`() {
    val nowTs = 1_000_000UL
    val schedule = forksScheduleWithoutFutureDifficultyAware(nowTs)
    val fixedClock = Clock.fixed(Instant.ofEpochSecond(nowTs.toLong()), ZoneOffset.UTC)

    MaruAppFactory().checkL2EthApiEndpointAndForks(fixedClock, schedule, null)
  }
}
