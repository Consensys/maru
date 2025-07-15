/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.config

import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class PayloadValidatorDto(
  val engineApiEndpoint: ApiEndpointDto,
  val ethApiEndpoint: ApiEndpointDto,
) {
  fun domainFriendly(): ValidatorElNode =
    ValidatorElNode(
      ethApiEndpoint = ethApiEndpoint.domainFriendly(),
      engineApiEndpoint = engineApiEndpoint.domainFriendly(),
    )
}

data class ApiEndpointDto(
  val endpoint: URL,
  val jwtSecretPath: String? = null,
) {
  fun domainFriendly(): ApiEndpointConfig = ApiEndpointConfig(endpoint = endpoint, jwtSecretPath = jwtSecretPath)
}

data class QbftOptionsDtoToml(
  val minBlockBuildTime: Duration = 500.milliseconds,
  val messageQueueLimit: Int = 1000,
  val roundExpiry: Duration = 1.seconds,
  val duplicateMessageLimit: Int = 100,
  val futureMessageMaxDistance: Long = 10L,
  val futureMessagesLimit: Long = 1000L,
  val feeRecipient: ByteArray,
) {
  fun toDomain(): QbftOptions =
    QbftOptions(
      minBlockBuildTime = minBlockBuildTime,
      messageQueueLimit = messageQueueLimit,
      roundExpiry = roundExpiry,
      duplicateMessageLimit = duplicateMessageLimit,
      futureMessageMaxDistance = futureMessageMaxDistance,
      futureMessagesLimit = futureMessagesLimit,
      feeRecipient = feeRecipient,
    )
}

data class MaruConfigDtoToml(
  private val allowEmptyBlocks: Boolean = false,
  private val persistence: Persistence,
  private val qbftOptions: QbftOptions?,
  private val p2pConfig: P2P?,
  private val payloadValidator: PayloadValidatorDto,
  private val followerEngineApis: Map<String, ApiEndpointDto>?,
  private val observabilityOptions: ObservabilityOptions,
  private val apiConfig: ApiConfig,
) {
  fun domainFriendly(): MaruConfig =
    MaruConfig(
      allowEmptyBlocks = allowEmptyBlocks,
      persistence = persistence,
      qbftOptions = qbftOptions,
      p2pConfig = p2pConfig,
      validatorElNode = payloadValidator.domainFriendly(),
      followers =
        FollowersConfig(
          followers = followerEngineApis?.mapValues { it.value.domainFriendly() } ?: emptyMap(),
        ),
      observabilityOptions = observabilityOptions,
      apiConfig = apiConfig,
    )
}
