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
package maru.core

import kotlin.random.Random
import kotlin.random.nextULong
import maru.core.ext.DataGenerators.randomBeaconBlockBody
import maru.core.ext.DataGenerators.randomBeaconBlockHeader
import maru.serialization.rlp.RLPCommitSealSerializers
import maru.serialization.rlp.RLPOnChainSerializers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HashUtilTest {
  @Test
  fun `can hash body ignoring commit seals`() {
    val beaconBlockBody1 = randomBeaconBlockBody()
    val beaconBlockBody2 = beaconBlockBody1.copy(commitSeals = (1..3).map { Seal(Random.nextBytes(96)) })
    assertThat(
      HashUtil.bodyRoot(RLPCommitSealSerializers.BeaconBlockBodySerializer, beaconBlockBody1),
    ).isEqualTo(HashUtil.bodyRoot(RLPCommitSealSerializers.BeaconBlockBodySerializer, beaconBlockBody2))
  }

  @Test
  fun `can hash body with empty prev commit seals`() {
    val beaconBlockBody = randomBeaconBlockBody().copy(prevCommitSeals = emptyList())
    assertThat(HashUtil.bodyRoot(RLPCommitSealSerializers.BeaconBlockBodySerializer, beaconBlockBody)).isNotEqualTo("")
  }

  @Test
  fun `can hash header for onchain omitting round number`() {
    val beaconBlockHeader1 = randomBeaconBlockHeader(Random.nextULong())
    val beaconBlockHeader2 = beaconBlockHeader1.copy(round = Random.nextULong())
    assertThat(
      HashUtil.headerOnChainHash(RLPOnChainSerializers.BeaconBlockHeaderSerializer, beaconBlockHeader1),
    ).isEqualTo(HashUtil.headerOnChainHash(RLPOnChainSerializers.BeaconBlockHeaderSerializer, beaconBlockHeader2))
  }

  @Test
  fun `can hash header for commit seal including round number`() {
    val beaconBlockHeader1 = randomBeaconBlockHeader(Random.nextULong())
    val beaconBlockHeader2 = beaconBlockHeader1.copy(round = Random.nextULong())
    assertThat(
      HashUtil.headerCommittedSealHash(RLPCommitSealSerializers.BeaconBlockHeaderSerializer, beaconBlockHeader1),
    ).isNotEqualTo(HashUtil.headerOnChainHash(RLPCommitSealSerializers.BeaconBlockHeaderSerializer, beaconBlockHeader2))
  }
}
