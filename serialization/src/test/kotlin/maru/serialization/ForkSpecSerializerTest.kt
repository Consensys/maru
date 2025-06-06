/*
   Copyright 2025 Consensys Software Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package maru.serialization

import maru.config.consensus.qbft.QbftConsensusConfig
import maru.config.consensus.qbft.QbftConsensusConfig.Companion.ElFork
import maru.consensus.ForkSpec
import maru.core.ext.DataGenerators
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForkSpecSerializerTest {
  @Test
  fun `serialization is deterministic for same input`() {
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
        blockTimeSeconds = 5,
        timestampSeconds = 123456789L,
        configuration = config1,
      )
    val forkSpec2 =
      ForkSpec(
        blockTimeSeconds = 5,
        timestampSeconds = 123456789L,
        configuration = config2,
      )
    val bytes1 = ForkIdSerializers.ForkSpecSerializer.serialize(forkSpec1)
    val bytes2 = ForkIdSerializers.ForkSpecSerializer.serialize(forkSpec2)
    assertThat(bytes1).isEqualTo(bytes2)
  }

  @Test
  fun `serialization changes when blockTimeSeconds changes`() {
    val v = DataGenerators.randomValidator()
    val config =
      QbftConsensusConfig(
        validatorSet = setOf(v),
        elFork = ElFork.Prague,
      )
    val forkSpec1 =
      ForkSpec(
        blockTimeSeconds = 5,
        timestampSeconds = 123456789L,
        configuration = config,
      )
    val forkSpec2 =
      forkSpec1.copy(blockTimeSeconds = 10)
    val bytes1 = ForkIdSerializers.ForkSpecSerializer.serialize(forkSpec1)
    val bytes2 = ForkIdSerializers.ForkSpecSerializer.serialize(forkSpec2)
    assertThat(bytes1).isNotEqualTo(bytes2)
  }

  @Test
  fun `serialization changes when timestampSeconds changes`() {
    val v = DataGenerators.randomValidator()
    val config =
      QbftConsensusConfig(
        validatorSet = setOf(v),
        elFork = ElFork.Prague,
      )
    val forkSpec1 =
      ForkSpec(
        blockTimeSeconds = 5,
        timestampSeconds = 123456789L,
        configuration = config,
      )
    val forkSpec2 = forkSpec1.copy(timestampSeconds = 3123L)
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
        blockTimeSeconds = 5,
        timestampSeconds = 123456789L,
        configuration = config1,
      )
    val forkSpec2 =
      ForkSpec(
        blockTimeSeconds = 5,
        timestampSeconds = 123456789L,
        configuration = config2,
      )
    val bytes1 = ForkIdSerializers.ForkSpecSerializer.serialize(forkSpec1)
    val bytes2 = ForkIdSerializers.ForkSpecSerializer.serialize(forkSpec2)
    assertThat(bytes1).isNotEqualTo(bytes2)
  }
}
