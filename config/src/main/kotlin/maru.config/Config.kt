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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class Persistence(
  val dataPath: Path,
  val privateKeyPath: Path = dataPath.resolve("private-key"),
)

data class ApiEndpointConfig(
  val endpoint: URL,
  val jwtSecretPath: String? = null,
)

data class FollowersConfig(
  val followers: Map<String, ApiEndpointConfig>,
)

data class P2P(
  val ipAddress: String,
  val port: String,
  val discoveryPort: String,
  val bootnodes: List<String> = emptyList(),
  val staticPeers: List<String> = emptyList(),
  val reconnectDelay: Duration = 5.seconds,
)

data class ValidatorElNode(
  val ethApiEndpoint: ApiEndpointConfig,
  val engineApiEndpoint: ApiEndpointConfig,
)

data class QbftOptions(
  val minBlockBuildTime: Duration = 500.milliseconds,
  val messageQueueLimit: Int = 1000,
  val roundExpiry: Duration = 1.seconds,
  val duplicateMessageLimit: Int = 100,
  val futureMessageMaxDistance: Long = 10L,
  val futureMessagesLimit: Long = 1000L,
)

data class MaruConfig(
  val persistence: Persistence,
  val qbftOptions: QbftOptions?,
  val p2pConfig: P2P?,
  val validatorElNode: ValidatorElNode,
  val followers: FollowersConfig,
)
