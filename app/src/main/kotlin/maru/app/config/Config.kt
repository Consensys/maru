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
package maru.app.config

import java.net.URL

data class ExecutionClientConfig(val endpoint: URL)

data class P2P(val port: UInt = 9000u)

data class Validator(val validatorKey: ByteArray) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as Validator

    if (!validatorKey.contentEquals(other.validatorKey)) return false

    return true
  }

  override fun hashCode(): Int {
    return validatorKey.contentHashCode()
  }
}

data class BeaconGenesisConfig(val blockTimeMillis: UInt) {
  init {
    require(blockTimeMillis >= 0u) { "blockTimeMillis must be greater than zero" }
  }
}

data class MaruConfig(
  val executionClientConfig: ExecutionClientConfig,
  val p2pConfig: P2P?,
  val validator: Validator?,
)
