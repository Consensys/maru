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

import maru.extensions.encodeHex

data class BeaconBlockHeader(
  val number: ULong,
  val round: UInt,
  val timestamp: ULong,
  val proposer: Validator,
  val parentRoot: ByteArray,
  val stateRoot: ByteArray,
  val bodyRoot: ByteArray,
  private val headerHashFunction: HeaderHashFunction,
) {
  val hash by lazy { headerHashFunction(this) }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as BeaconBlockHeader

    return hash.contentEquals(other.hash)
  }

  override fun hashCode(): Int = hash.contentHashCode()

  fun hash(): ByteArray = hash

  override fun toString(): String =
    """
    BeaconBlockHeader(
      number=$number,
      round=$round,
      timestamp=$timestamp,
      proposer=$proposer,
      parentRoot=${parentRoot.encodeHex()},
      stateRoot=${stateRoot.encodeHex()},
      bodyRoot=${bodyRoot.encodeHex()}
    """.trimIndent()
}
