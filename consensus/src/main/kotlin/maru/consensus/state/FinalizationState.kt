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
package maru.consensus.state

data class FinalizationState(
  val safeBlockHash: ByteArray,
  val finalizedBlockHash: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as FinalizationState

    if (!safeBlockHash.contentEquals(other.safeBlockHash)) return false
    if (!finalizedBlockHash.contentEquals(other.finalizedBlockHash)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = safeBlockHash.contentHashCode()
    result = 31 * result + finalizedBlockHash.contentHashCode()
    return result
  }
}
