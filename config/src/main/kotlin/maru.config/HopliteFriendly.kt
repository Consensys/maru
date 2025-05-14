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

import com.sksamuel.hoplite.Masked
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import maru.core.Validator
import maru.extensions.fromHexToByteArray

data class QbftOptionsDtoTomlFriendly(
  val validatorDuties: ValidatorDutiesDtoTomlFriendly?,
  val validatorSet: Set<String>,
) {
  fun toDomain(): QbftOptions {
    val validatorSet = validatorSet.map { Validator(it.fromHexToByteArray()) }.toSet()
    return QbftOptions(validatorDuties = validatorDuties?.toDomain(), validatorSet = validatorSet)
  }
}

data class ValidatorDutiesDtoTomlFriendly(
  val privateKey: Masked,
  // Since we cannot finish block production instantly at expected time, we need to set some safety margin
  val communicationMargin: Duration,
  val messageQueueLimit: Int = 1000,
  val roundExpiry: Duration = 1.seconds,
  val duplicateMessageLimit: Int = 100,
  val futureMessageMaxDistance: Long = 10L,
  val futureMessagesLimit: Long = 1000L,
) {
  fun toDomain(): ValidatorDuties =
    ValidatorDuties(
      privateKey = privateKey.value.fromHexToByteArray(),
      communicationMargin = communicationMargin,
      messageQueueLimit = messageQueueLimit,
      roundExpiry = roundExpiry,
      duplicateMessageLimit = duplicateMessageLimit,
      futureMessageMaxDistance = futureMessageMaxDistance,
      futureMessagesLimit = futureMessagesLimit,
    )
}

data class PayloadValidatorDtoToml(
  val engineApiEndpoint: ApiEndpointDtoToml,
  val ethApiEndpoint: ApiEndpointDtoToml,
) {
  fun domainFriendly(): ValidatorElNode =
    ValidatorElNode(
      ethApiEndpoint = ethApiEndpoint.toDomain(),
      engineApiEndpoint = engineApiEndpoint.toDomain(),
    )
}

data class ApiEndpointDtoToml(
  val endpoint: URL,
  val jwtSecretPath: String? = null,
) {
  fun toDomain(): ApiEndpointConfig = ApiEndpointConfig(endpoint = endpoint, jwtSecretPath = jwtSecretPath)
}

data class MaruConfigDtoToml(
  private val persistence: Persistence,
  private val qbftOptions: QbftOptionsDtoTomlFriendly,
  private val p2pConfig: P2P?,
  private val payloadValidator: PayloadValidatorDtoToml,
  private val followerEngineApis: Map<String, ApiEndpointDtoToml>?,
) {
  fun domainFriendly(): MaruConfig =
    MaruConfig(
      persistence = persistence,
      qbftOptions = qbftOptions.toDomain(),
      p2pConfig = p2pConfig,
      validatorElNode = payloadValidator.domainFriendly(),
      followers = FollowersConfig(followers = followerEngineApis?.mapValues { it.value.toDomain() } ?: emptyMap()),
    )
}
