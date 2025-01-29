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
package maru.consensus.dummy

import kotlin.math.min
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest

class TimeDrivenEventProducerTest {
  @RepeatedTest(100)
  fun `nextBlockDelay always targets an even second`() {
    val currentTimestampMillis = Random.nextLong(0L, 10000L)
    val lastBlockTimestampSeconds =
      currentTimestampMillis - min(
        Random.nextLong(0L, 10000L),
        currentTimestampMillis,
      ) / 1000L
    val nextBlockPeriodMillis = 1000

    val delay =
      TimeDrivenEventProducer.nextBlockDelay(
        currentTimestampMillis,
        lastBlockTimestampSeconds,
        nextBlockPeriodMillis,
      )

    assertThat((delay.toLong() + currentTimestampMillis) % 1000).isEqualTo(0)
  }
}
