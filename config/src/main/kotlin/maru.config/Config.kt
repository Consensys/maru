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
package maru.config

import java.net.URL
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class Persistence(
  val dataPath: Path,
)

data class ApiEndpointConfig(
  val endpoint: URL,
  val jwtSecretPath: String? = null,
)

data class FollowersConfig(
  val followers: Map<String, ApiEndpointConfig>,
)

data class P2P(
  val port: UInt = 9000u,
)

data class Validator(
  val ethApiEndpoint: ApiEndpointConfig,
  val engineApiEndpint: ApiEndpointConfig,
)

data class QbftOptions(
  val privateKey: ByteArray,
  // Since we cannot finish block production instantly at expected time, we need to set some safety margin
  val communicationMargin: Duration,
  val messageQueueLimit: Int = 1000,
  val roundExpiry: Duration = 1.seconds,
  val duplicateMessageLimit: Int = 100,
  val futureMessageMaxDistance: Long = 10L,
  val futureMessagesLimit: Long = 1000L,
) {
  init {
    require(privateKey.size == 32) {
      "validator key must be 32 bytes long"
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as QbftOptions

    if (messageQueueLimit != other.messageQueueLimit) return false
    if (duplicateMessageLimit != other.duplicateMessageLimit) return false
    if (futureMessageMaxDistance != other.futureMessageMaxDistance) return false
    if (futureMessagesLimit != other.futureMessagesLimit) return false
    if (!privateKey.contentEquals(other.privateKey)) return false
    if (communicationMargin != other.communicationMargin) return false
    if (roundExpiry != other.roundExpiry) return false

    return true
  }

  override fun hashCode(): Int {
    var result = messageQueueLimit
    result = 31 * result + duplicateMessageLimit
    result = 31 * result + futureMessageMaxDistance.hashCode()
    result = 31 * result + futureMessagesLimit.hashCode()
    result = 31 * result + privateKey.contentHashCode()
    result = 31 * result + communicationMargin.hashCode()
    result = 31 * result + roundExpiry.hashCode()
    return result
  }
}

data class MaruConfig(
  val persistence: Persistence,
  val qbftOptions: QbftOptions?,
  val p2pConfig: P2P?,
  val validator: Validator,
  val followers: FollowersConfig,
)
