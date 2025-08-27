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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import linea.domain.BlockParameter
import linea.domain.RetryConfig
import linea.kotlin.assertIs20Bytes

data class PayloadValidatorDto(
  val engineApiEndpoint: ApiEndpointDto,
  val ethApiEndpoint: ApiEndpointDto,
) {
  fun domainFriendly(): ValidatorElNode =
    ValidatorElNode(
      ethApiEndpoint = ethApiEndpoint.domainFriendly(endlessRetries = true),
      engineApiEndpoint = engineApiEndpoint.domainFriendly(endlessRetries = true),
    )
}

data class ApiEndpointDto(
  val endpoint: URL,
  val jwtSecretPath: String? = null,
  val timeout: Duration = 1.minutes,
) {
  fun domainFriendly(endlessRetries: Boolean = false): ApiEndpointConfig {
    val retries =
      if (endlessRetries) {
        RetryConfig.endlessRetry(
          backoffDelay = 1.seconds,
          failuresWarningThreshold = 3u,
        )
      } else {
        RetryConfig.noRetries
      }
    return ApiEndpointConfig(
      endpoint = endpoint,
      jwtSecretPath = jwtSecretPath,
      requestRetries = retries,
      timeout = timeout,
    )
  }
}

data class QbftOptionsDtoToml(
  val minBlockBuildTime: Duration = 500.milliseconds,
  val messageQueueLimit: Int = 1000,
  val roundExpiry: Duration? = null,
  val duplicateMessageLimit: Int = 100,
  val futureMessageMaxDistance: Long = 10L,
  val futureMessagesLimit: Long = 1000L,
  val feeRecipient: ByteArray,
) {
  fun toDomain(): QbftConfig =
    QbftConfig(
      minBlockBuildTime = minBlockBuildTime,
      messageQueueLimit = messageQueueLimit,
      roundExpiry = roundExpiry,
      duplicateMessageLimit = duplicateMessageLimit,
      futureMessageMaxDistance = futureMessageMaxDistance,
      futureMessagesLimit = futureMessagesLimit,
      feeRecipient = feeRecipient,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as QbftOptionsDtoToml

    if (messageQueueLimit != other.messageQueueLimit) return false
    if (duplicateMessageLimit != other.duplicateMessageLimit) return false
    if (futureMessageMaxDistance != other.futureMessageMaxDistance) return false
    if (futureMessagesLimit != other.futureMessagesLimit) return false
    if (minBlockBuildTime != other.minBlockBuildTime) return false
    if (roundExpiry != other.roundExpiry) return false
    if (!feeRecipient.contentEquals(other.feeRecipient)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = messageQueueLimit
    result = 31 * result + duplicateMessageLimit
    result = 31 * result + futureMessageMaxDistance.hashCode()
    result = 31 * result + futureMessagesLimit.hashCode()
    result = 31 * result + minBlockBuildTime.hashCode()
    result = 31 * result + roundExpiry.hashCode()
    result = 31 * result + feeRecipient.contentHashCode()
    return result
  }
}

data class LineaConfigDtoToml(
  val contractAddress: ByteArray,
  val l1EthApi: ApiEndpointDto,
  val l1PollingInterval: Duration = 6.seconds,
  val l1HighestBlockTag: String = "finalized",
) {
  init {
    contractAddress.assertIs20Bytes("contractAddress")
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as LineaConfigDtoToml

    if (!contractAddress.contentEquals(other.contractAddress)) return false
    if (l1EthApi != other.l1EthApi) return false
    if (l1PollingInterval != other.l1PollingInterval) return false
    if (l1HighestBlockTag != other.l1HighestBlockTag) return false

    return true
  }

  override fun hashCode(): Int {
    var result = contractAddress.contentHashCode()
    result = 31 * result + l1EthApi.hashCode()
    result = 31 * result + l1PollingInterval.hashCode()
    result = 31 * result + l1HighestBlockTag.hashCode()
    return result
  }

  fun domainFriendly(): LineaConfig =
    LineaConfig(
      contractAddress = contractAddress,
      l1EthApi = l1EthApi.domainFriendly(),
      l1PollingInterval = l1PollingInterval,
      l1HighestBlockTag = BlockParameter.parse(l1HighestBlockTag),
    )
}

data class MaruConfigDtoToml(
  private val linea: LineaConfigDtoToml? = null,
  private val protocolTransitionPollingInterval: Duration = 1.seconds,
  private val allowEmptyBlocks: Boolean = false,
  private val persistence: Persistence,
  private val qbft: QbftOptionsDtoToml?,
  private val p2p: P2PConfig?,
  private val payloadValidator: PayloadValidatorDto,
  private val followerEngineApis: Map<String, ApiEndpointDto>?,
  private val observability: ObservabilityConfig,
  private val api: ApiConfig,
  private val syncing: SyncingConfig,
) {
  fun domainFriendly(): MaruConfig =
    MaruConfig(
      linea = linea?.domainFriendly(),
      protocolTransitionPollingInterval = protocolTransitionPollingInterval,
      allowEmptyBlocks = allowEmptyBlocks,
      persistence = persistence,
      qbft = qbft?.toDomain(),
      p2p = p2p,
      validatorElNode = payloadValidator.domainFriendly(),
      followers =
        FollowersConfig(
          followers = followerEngineApis?.mapValues { it.value.domainFriendly() } ?: emptyMap(),
        ),
      observability = observability,
      api = api,
      syncing = syncing,
    )
}
