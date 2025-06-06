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
package maru.p2p.messages

import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

class StatusSerializerTest {
  private val serializer = StatusSerializer()

  @Test
  fun `can serialize and deserialize same value`() {
    val testValue = Status(Random.nextBytes(32), Random.nextBytes(32), Random.nextULong())

    val serializedData = serializer.serialize(testValue)
    val deserializedValue = serializer.deserialize(serializedData)

    assertThat(deserializedValue).isEqualTo(testValue)
  }
}
