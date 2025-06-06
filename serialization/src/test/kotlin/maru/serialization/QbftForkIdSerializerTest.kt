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

import kotlin.random.Random
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.config.consensus.qbft.QbftConsensusConfig.Companion.ElFork
import maru.consensus.ForkId
import maru.consensus.ForkSpec
import maru.core.ext.DataGenerators
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

class QbftForkIdSerializerTest {
  private val dummyChainId: UInt = 1u

  @Test
  fun `serialization is deterministic for separate instances with the same values`() {
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
    val forkSpec1 = ForkSpec(1, 1, config1)
    val forkSpec2 = forkSpec1.copy(configuration = config2)
    val forkId1 = ForkId(dummyChainId, forkSpec1, Random.nextBytes(32))
    val forkId2 = forkId1.copy(forkSpec = forkSpec2)
    val bytes1 = ForkIdSerializers.ForkIdSerializer.serialize(forkId1)
    val bytes2 = ForkIdSerializers.ForkIdSerializer.serialize(forkId2)
    Assertions.assertThat(bytes1).isEqualTo(bytes2)
  }

  @Test
  fun `serialization changes when fork spec changes`() {
    val v1 = DataGenerators.randomValidator()
    val v2 = DataGenerators.randomValidator()
    val config =
      QbftConsensusConfig(
        validatorSet = setOf(v1, v2),
        elFork = ElFork.Prague,
      )
    val forkSpec1 = ForkSpec(1, 1, config)
    val forkSpec2 = forkSpec1.copy(blockTimeSeconds = 2)
    val forkId1 = ForkId(dummyChainId, forkSpec1, Random.nextBytes(32))
    val forkId2 = forkId1.copy(forkSpec = forkSpec2)
    val bytes1 = ForkIdSerializers.ForkIdSerializer.serialize(forkId1)
    val bytes2 = ForkIdSerializers.ForkIdSerializer.serialize(forkId2)
    Assertions.assertThat(bytes1).isNotEqualTo(bytes2)
  }

  @Test
  fun `serialization changes when chain id changes`() {
    val v1 = DataGenerators.randomValidator()
    val v2 = DataGenerators.randomValidator()
    val config =
      QbftConsensusConfig(
        validatorSet = setOf(v1, v2),
        elFork = ElFork.Prague,
      )
    val forkSpec = ForkSpec(1, 1, config)
    val forkId1 = ForkId(dummyChainId, forkSpec, Random.nextBytes(32))
    val forkId2 = forkId1.copy(chainId = 21U)
    val bytes1 = ForkIdSerializers.ForkIdSerializer.serialize(forkId1)
    val bytes2 = ForkIdSerializers.ForkIdSerializer.serialize(forkId2)
    Assertions.assertThat(bytes1).isNotEqualTo(bytes2)
  }

  @Test
  fun `serialization changes when genesis root hash changes`() {
    val v1 = DataGenerators.randomValidator()
    val v2 = DataGenerators.randomValidator()
    val config =
      QbftConsensusConfig(
        validatorSet = setOf(v1, v2),
        elFork = ElFork.Prague,
      )
    val forkSpec = ForkSpec(1, 1, config)
    val forkId1 = ForkId(dummyChainId, forkSpec, Random.nextBytes(32))
    val forkId2 = forkId1.copy(genesisRootHash = Random.nextBytes(32))
    val bytes1 = ForkIdSerializers.ForkIdSerializer.serialize(forkId1)
    val bytes2 = ForkIdSerializers.ForkIdSerializer.serialize(forkId2)
    Assertions.assertThat(bytes1).isNotEqualTo(bytes2)
  }
}
