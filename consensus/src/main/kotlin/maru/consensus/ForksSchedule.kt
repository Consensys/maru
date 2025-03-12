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
package maru.consensus

import java.util.NavigableSet
import java.util.TreeSet

interface ConsensusConfig

enum class ElFork {
  Prague,
}

data class ForkSpec(
  val timestampSeconds: Long,
  val blockTimeSeconds: Int,
  val configuration: ConsensusConfig,
) {
  init {
    require(blockTimeSeconds > 0) { "blockTimeSeconds must be greater or equal to 1 second" }
  }
}

class ForksSchedule(
  forks: Collection<ForkSpec>,
) {
  private val forks: NavigableSet<ForkSpec> =
    run {
      val newForks =
        TreeSet(
          Comparator.comparing(ForkSpec::timestampSeconds).reversed(),
        )
      newForks.addAll(forks)
      newForks
    }

  fun getForkByTimestamp(timestamp: Long): ForkSpec {
    for (f in forks) {
      if (timestamp >= f.timestampSeconds) {
        return f
      }
    }

    return forks.first()
  }

  fun getNextForkByTimestamp(timestamp: Long): ForkSpec {
    val currentFork = getForkByTimestamp(timestamp)
    return getForkByTimestamp(timestamp + currentFork.blockTimeSeconds)
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ForksSchedule

    return forks == other.forks
  }

  override fun hashCode(): Int = forks.hashCode()
}
