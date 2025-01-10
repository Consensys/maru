/*
   Copyright 2024 Consensys Software Inc.

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
package maru.consensus.core

/**
 * After every BeaconBlock there is a transition in the BeaconState by applying the operations from
 * the BeaconBlock These operations could be a new execution payload, adding/removing validators
 * etc.
 */
data class BeaconState(
  val latestBeaconBlockHeader: BeaconBlockHeader,
  val latestBeaconBlockRoot: ByteArray,
  val validators: Set<Validator>,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as BeaconState

    if (latestBeaconBlockHeader != other.latestBeaconBlockHeader) return false
    if (!latestBeaconBlockRoot.contentEquals(other.latestBeaconBlockRoot)) return false
    if (validators != other.validators) return false

    return true
  }

  override fun hashCode(): Int {
    var result = latestBeaconBlockHeader.hashCode()
    result = 31 * result + latestBeaconBlockRoot.contentHashCode()
    result = 31 * result + validators.hashCode()
    return result
  }
}
