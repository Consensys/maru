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
package maru.p2p

import kotlin.random.Random
import kotlin.random.nextUInt
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.config.consensus.qbft.QbftConsensusConfig.Companion.ElFork
import maru.consensus.ForkId
import maru.consensus.ForkIdHasher
import maru.core.ext.DataGenerators
import maru.crypto.Hashing
import maru.serialization.ForkIdSerializers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QbftForkIdComputerTest {
  private val dummyChainId: UInt = Random.nextUInt()

  @Test
  fun `hash is deterministic for same input`() {
    val v1 = DataGenerators.randomValidator()
    val v2 = DataGenerators.randomValidator()
    val config1 =
      QbftConsensusConfig(
        validatorSet = setOf(v1, v2),
        elFork = ElFork.Prague,
      )
    // The result is supposed to be the same, but we need a copy to prove that hash depends on data
    val config2 = config1.copy(validatorSet = config1.validatorSet)
    val genesisRoot1 = Random.nextBytes(32)
    val genesisRoot2 = genesisRoot1.clone()
    val forkId1 = ForkId(dummyChainId, config1, genesisRoot1)
    val forkId2 = ForkId(dummyChainId, config2, genesisRoot2)
    val hasher = ForkIdHasher(ForkIdSerializers.QbftForkIdSerializer, Hashing::shortShaHash)

    val hash1 = hasher.hash(forkId1)
    val hash2 = hasher.hash(forkId2)

    assertThat(hash1).isEqualTo(hash2)
  }

  @Test
  fun `hash changes when consensus config changes`() {
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
    val genesisRoot = Random.nextBytes(32)
    val forkId1 = ForkId(dummyChainId, config1, genesisRoot)
    val forkId2 = forkId1.copy(consensusConfig = config2)
    val hasher = ForkIdHasher(ForkIdSerializers.QbftForkIdSerializer, Hashing::shortShaHash)

    val hash1 = hasher.hash(forkId1)
    val hash2 = hasher.hash(forkId2)

    assertThat(hash1).isNotEqualTo(hash2)
  }

  @Test
  fun `hash changes when chainId changes`() {
    val v1 = DataGenerators.randomValidator()
    val v2 = DataGenerators.randomValidator()
    val config =
      QbftConsensusConfig(
        validatorSet = setOf(v1, v2),
        elFork = ElFork.Prague,
      )
    val genesisRoot = Random.nextBytes(32)
    val forkId1 = ForkId(1u, config, genesisRoot)
    val forkId2 = forkId1.copy(2u)
    val hasher = ForkIdHasher(ForkIdSerializers.QbftForkIdSerializer, Hashing::shortShaHash)

    val hash1 = hasher.hash(forkId1)
    val hash2 = hasher.hash(forkId2)

    assertThat(hash1).isNotEqualTo(hash2)
  }

  @Test
  fun `hash changes when genesisRootHash changes`() {
    val v1 = DataGenerators.randomValidator()
    val v2 = DataGenerators.randomValidator()
    val config =
      QbftConsensusConfig(
        validatorSet = setOf(v1, v2),
        elFork = ElFork.Prague,
      )
    val genesisRoot1 = Random.nextBytes(32)
    val genesisRoot2 = Random.nextBytes(32)
    val forkId1 = ForkId(dummyChainId, config, genesisRoot1)
    val forkId2 = forkId1.copy(genesisRootHash = genesisRoot2)
    val hasher = ForkIdHasher(ForkIdSerializers.QbftForkIdSerializer, Hashing::shortShaHash)

    val hash1 = hasher.hash(forkId1)
    val hash2 = hasher.hash(forkId2)

    assertThat(hash1).isNotEqualTo(hash2)
  }
}
