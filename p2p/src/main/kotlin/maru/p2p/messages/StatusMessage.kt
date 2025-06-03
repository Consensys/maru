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

data class StatusMessage(
  val forkId: ByteArray,
  val headStateRoot: ByteArray,
  val headBlockNumber: ULong,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is StatusMessage) return false

    if (forkId != other.forkId) return false
    if (!headStateRoot.contentEquals(other.headStateRoot)) return false
    if (headBlockNumber != other.headBlockNumber) return false

    return true
  }

  override fun hashCode(): Int {
    var result = forkId.hashCode()
    result = 31 * result + headStateRoot.contentHashCode()
    result = 31 * result + headBlockNumber.hashCode()
    return result
  }
}
