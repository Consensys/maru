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
import maru.core.BeaconBlockBody
import maru.core.Seal
import maru.core.ext.DataGenerators.randomExecutionPayload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BeaconBlockBodySerializerTest {
  private val serializer =
    BeaconBlockBodySerializer(
      sealSerializer = SealSerializer(),
      executionPayloadSerializer = ExecutionPayloadSerializer(),
    )

  @Test
  fun `can serialize and deserialize same value`() {
    val testValue =
      BeaconBlockBody(
        prevBlockSeals = buildList(3) { Seal(Random.nextBytes(96)) },
        executionPayload =
          randomExecutionPayload(),
      )
    val serializedData = serializer.serialize(testValue)
    val deserializedValue = serializer.deserialize(serializedData)

    assertThat(deserializedValue).isEqualTo(testValue)
  }
}
