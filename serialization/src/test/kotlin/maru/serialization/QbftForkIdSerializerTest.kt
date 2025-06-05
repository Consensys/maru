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
import maru.consensus.ForkId
import maru.core.ext.DataGenerators
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

class QbftForkIdSerializerTest {
  private val dummyChainId: UInt = 1u

  @Test
  fun `serialization is deterministic regardless of validator set order`() {
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
    val forkId1 = ForkId(dummyChainId, config1, ByteArray(32) { 0x01 })
    val forkId2 = ForkId(dummyChainId, config2, ByteArray(32) { 0x01 })
    val bytes1 = ForkIdSerializers.QbftForkIdSerializer.serialize(forkId1)
    val bytes2 = ForkIdSerializers.QbftForkIdSerializer.serialize(forkId2)
    Assertions.assertThat(bytes1).isEqualTo(bytes2)
  }

  @Test
  fun `serialization changes when validator set changes`() {
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
    val forkId1 = ForkId(dummyChainId, config1, ByteArray(32) { 0x01 })
    val forkId2 = ForkId(dummyChainId, config2, ByteArray(32) { 0x01 })
    val bytes1 = ForkIdSerializers.QbftForkIdSerializer.serialize(forkId1)
    val bytes2 = ForkIdSerializers.QbftForkIdSerializer.serialize(forkId2)
    Assertions.assertThat(bytes1).isNotEqualTo(bytes2)
  }
}
