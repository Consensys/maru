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
package maru.serialization.rlp

import kotlin.random.Random
import kotlin.random.nextULong
import maru.core.BeaconState
import maru.core.HashUtil
import maru.core.Validator
import maru.core.ext.DataGenerators
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BeaconStateSerializerTest {
  private val validatorSerializer = ValidatorSerializer()
  private val beaconBlockHeaderSerializer =
    BeaconBlockHeaderSerializer(
      validatorSerializer = validatorSerializer,
      hasher = KeccakHasher,
      headerHashFunction = HashUtil::headerHash,
    )
  private val serializer =
    BeaconStateSerializer(
      beaconBlockHeaderSerializer = beaconBlockHeaderSerializer,
      validatorSerializer = validatorSerializer,
    )

  @Test
  fun `can serialize and deserialize same value`() {
    val beaconBLockHeader = DataGenerators.randomBeaconBlockHeader(Random.nextULong())
    val testValue =
      BeaconState(
        latestBeaconBlockHeader = beaconBLockHeader,
        validators = buildSet(3) { Validator(Random.nextBytes(20)) },
      )
    val serializedData = serializer.serialize(testValue)
    val deserializedValue = serializer.deserialize(serializedData)

    assertThat(deserializedValue).isEqualTo(testValue)
  }
}
