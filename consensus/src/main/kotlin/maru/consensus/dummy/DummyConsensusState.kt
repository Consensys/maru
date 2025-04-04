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

import java.time.Clock
import maru.consensus.state.FinalizationState

data class DummyConsensusState(
  val clock: Clock,
  @Volatile private var finalizationState_: FinalizationState,
  @Volatile var latestBlockHash_: ByteArray,
) {
  val finalizationState: FinalizationState get() = finalizationState_
  val latestBlockHash: ByteArray get() = latestBlockHash_

  @Synchronized
  fun updateFinalizationState(finalizationState: FinalizationState) {
    finalizationState_ = finalizationState
  }

  @Synchronized
  fun updateLatestStatus(latestBlockHash: ByteArray) {
    latestBlockHash_ = latestBlockHash
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as DummyConsensusState

    if (clock != other.clock) return false
    if (finalizationState_ != other.finalizationState_) return false
    if (!latestBlockHash_.contentEquals(other.latestBlockHash_)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = clock.hashCode()
    result = 31 * result + finalizationState_.hashCode()
    result = 31 * result + latestBlockHash_.contentHashCode()
    return result
  }
}
