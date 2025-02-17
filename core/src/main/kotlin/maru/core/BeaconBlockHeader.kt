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

data class BeaconBlockHeader(
  val number: ULong,
  val round: ULong,
  val timestamp: ULong,
  val proposer: Validator,
  val parentRoot: ByteArray,
  val stateRoot: ByteArray,
  val bodyRoot: ByteArray,
  val hashFunction: HashFunction,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as BeaconBlockHeader

    if (number != other.number) return false
    if (round != other.round) return false
    if (timestamp != other.timestamp) return false
    if (proposer != other.proposer) return false
    if (!parentRoot.contentEquals(other.parentRoot)) return false
    if (!stateRoot.contentEquals(other.stateRoot)) return false
    if (!bodyRoot.contentEquals(other.bodyRoot)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = number.hashCode()
    result = 31 * result + round.hashCode()
    result = 31 * result + timestamp.hashCode()
    result = 31 * result + proposer.hashCode()
    result = 31 * result + parentRoot.contentHashCode()
    result = 31 * result + stateRoot.contentHashCode()
    result = 31 * result + bodyRoot.contentHashCode()
    return result
  }

  // TODO cache hash
  fun hash(): ByteArray = hashFunction.invoke(this)
}
