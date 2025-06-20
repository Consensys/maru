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
  val port: UInt,
  val discoveryPort: UInt,
  val bootnodes: List<String> = emptyList(),
  val staticPeers: List<String> = emptyList(),
  val reconnectDelay: Duration = 5.seconds,
  val maxPeers: Int = 25,
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
  val feeRecipient: ByteArray,
) {
  init {
    require(feeRecipient.size == 20) {
      "feeRecipient address must be 20 bytes long, " +
        "but it's ${feeRecipient.size} bytes long!"
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

data class ObservabilityOptions(
  val port: UInt,
  val prometheusMetricsEnabled: Boolean = true,
  val jvmMetricsEnabled: Boolean = true,
)

data class MaruConfig(
  val persistence: Persistence,
  val qbftOptions: QbftOptions?,
  val p2pConfig: P2P?,
  val validatorElNode: ValidatorElNode,
  val followers: FollowersConfig,
  val observabilityOptions: ObservabilityOptions,
)
