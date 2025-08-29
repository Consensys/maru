/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.serialization

import maru.config.consensus.ElFork
import maru.config.consensus.delegated.ElDelegatedConfig
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ForkSpec
import maru.core.ext.DataGenerators
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForkSpecSerializerTest {
  private val config =
    ElDelegatedConfig(
      postTtdConfig =
        QbftConsensusConfig(
          setOf(DataGenerators.randomValidator(), DataGenerators.randomValidator()),
          elFork =
            ElFork
              .Paris,
        ),
      switchBlockNumber = 123UL,
    )

  @Test
  fun `serialization for Qbft is deterministic for same input`() {
    val v1 = DataGenerators.randomValidator()
    val v2 = DataGenerators.randomValidator()
    val config1 =
      QbftConsensusConfig(
        validatorSet = setOf(v1, v2),
        elFork = ElFork.Prague,
      )
    val config2 =
      QbftConsensusConfig(
        validatorSet = setOf(v2, v1),
        elFork = ElFork.Prague,
      )
    val forkSpec1 =
      ForkSpec(
        blockTimeSeconds = 5u,
        timestampSeconds = 123456789UL,
        configuration = config1,
      )
    val forkSpec2 =
      ForkSpec(
        blockTimeSeconds = 5u,
        timestampSeconds = 123456789UL,
        configuration = config2,
      )
    val bytes1 = ForkSpecSerializer.ForkSpecSerializer.serialize(forkSpec1)
    val bytes2 = ForkSpecSerializer.ForkSpecSerializer.serialize(forkSpec2)
    assertThat(bytes1).isEqualTo(bytes2)
  }

  @Test
  fun `serialization for ELDelegated is deterministic for same input`() {
    val forkSpec1 =
      ForkSpec(
        blockTimeSeconds = 5u,
        timestampSeconds = 123456789UL,
        configuration = config,
      )
    val forkSpec2 =
      ForkSpec(
        blockTimeSeconds = 5u,
        timestampSeconds = 123456789UL,
        configuration = config,
      )
    val bytes1 = ForkIdSerializers.ForkSpecSerializer.serialize(forkSpec1)
    val bytes2 = ForkIdSerializers.ForkSpecSerializer.serialize(forkSpec2)
    assertThat(bytes1).isEqualTo(bytes2)
  }

  @Test
  fun `serialization changes for ELDelegated when blockTimeSeconds changes`() {
    val forkSpec1 =
      ForkSpec(
        blockTimeSeconds = 5u,
        timestampSeconds = 123456789UL,
        configuration = config,
      )
    val forkSpec2 =
      forkSpec1.copy(blockTimeSeconds = 10U)
    val bytes1 = ForkIdSerializers.ForkSpecSerializer.serialize(forkSpec1)
    val bytes2 = ForkIdSerializers.ForkSpecSerializer.serialize(forkSpec2)
    assertThat(bytes1).isNotEqualTo(bytes2)
  }

  @Test
  fun `serialization changes for QBFT when blockTimeSeconds changes`() {
    val v = DataGenerators.randomValidator()
    val config =
      QbftConsensusConfig(
        validatorSet = setOf(v),
        elFork = ElFork.Prague,
      )
    val forkSpec1 =
      ForkSpec(
        blockTimeSeconds = 5U,
        timestampSeconds = 123456789UL,
        configuration = config,
      )
    val forkSpec2 =
      forkSpec1.copy(blockTimeSeconds = 10U)
    val bytes1 = ForkSpecSerializer.ForkSpecSerializer.serialize(forkSpec1)
    val bytes2 = ForkSpecSerializer.ForkSpecSerializer.serialize(forkSpec2)
    assertThat(bytes1).isNotEqualTo(bytes2)
  }

  @Test
  fun `serialization for QBFT changes when timestampSeconds changes`() {
    val v = DataGenerators.randomValidator()
    val config =
      QbftConsensusConfig(
        validatorSet = setOf(v),
        elFork = ElFork.Prague,
      )
    val forkSpec1 =
      ForkSpec(
        blockTimeSeconds = 5U,
        timestampSeconds = 123456789UL,
        configuration = config,
      )
    val forkSpec2 = forkSpec1.copy(timestampSeconds = 3123UL)
    val bytes1 = ForkSpecSerializer.ForkSpecSerializer.serialize(forkSpec1)
    val bytes2 = ForkSpecSerializer.ForkSpecSerializer.serialize(forkSpec2)
    assertThat(bytes1).isNotEqualTo(bytes2)
  }

  @Test
  fun `serialization for ELDelegated changes when timestampSeconds changes`() {
    val forkSpec1 =
      ForkSpec(
        blockTimeSeconds = 5U,
        timestampSeconds = 123456789UL,
        configuration = config,
      )
    val forkSpec2 = forkSpec1.copy(timestampSeconds = 3123UL)
    val bytes1 = ForkIdSerializers.ForkSpecSerializer.serialize(forkSpec1)
    val bytes2 = ForkIdSerializers.ForkSpecSerializer.serialize(forkSpec2)
    assertThat(bytes1).isNotEqualTo(bytes2)
  }

  @Test
  fun `serialization changes when consensus config changes`() {
    val v1 = DataGenerators.randomValidator()
    val v2 = DataGenerators.randomValidator()
    val config1 =
      QbftConsensusConfig(
        validatorSet = setOf(v1),
        elFork = ElFork.Prague,
      )
    val config2 =
      QbftConsensusConfig(
        validatorSet = setOf(v1, v2),
        elFork = ElFork.Prague,
      )
    val forkSpec1 =
      ForkSpec(
        blockTimeSeconds = 5U,
        timestampSeconds = 123456789UL,
        configuration = config1,
      )
    val forkSpec2 =
      ForkSpec(
        blockTimeSeconds = 5U,
        timestampSeconds = 123456789UL,
        configuration = config2,
      )
    val bytes1 = ForkSpecSerializer.ForkSpecSerializer.serialize(forkSpec1)
    val bytes2 = ForkSpecSerializer.ForkSpecSerializer.serialize(forkSpec2)
    assertThat(bytes1).isNotEqualTo(bytes2)
  }
}
