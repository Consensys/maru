/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.test.cluster

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import maru.config.ApiConfig
import maru.config.ApiEndpointConfig
import maru.config.FollowersConfig
import maru.config.MaruConfig
import maru.config.ObservabilityConfig
import maru.config.P2PConfig
import maru.config.P2PConfig.Discovery
import maru.config.Persistence
import maru.config.QbftConfig
import maru.config.SyncingConfig
import maru.config.SyncingConfig.SyncTargetSelection
import maru.config.ValidatorElNode
import maru.crypto.PrivateKeyGenerator
import maru.extensions.encodeHex

val configTemplate: MaruConfig =
  MaruConfig(
    allowEmptyBlocks = true,
    persistence = Persistence(dataPath = Path.of("maru-data")),
    validatorElNode =
      ValidatorElNode(
        payloadValidationEnabled = false,
        ethApiEndpoint = ApiEndpointConfig(URI.create("http://replace-me:8545").toURL()),
        engineApiEndpoint = ApiEndpointConfig(URI.create("http://replace-me:8551").toURL()),
      ),
    api = ApiConfig(port = 0u),
    qbft = null, // Followers by default
    p2p =
      P2PConfig(
        port = 0u, // find a free port
        discovery =
          Discovery(
            refreshInterval = 10.seconds,
            port = 0u,
            bootnodes = emptyList(),
          ),
      ),
    followers = FollowersConfig(emptyMap()),
    syncing =
      SyncingConfig(
        peerChainHeightPollingInterval = 1.seconds,
        syncTargetSelection = SyncTargetSelection.Highest,
        elSyncStatusRefreshInterval = 500.milliseconds,
      ),
    observability =
      ObservabilityConfig(
        port = 0u,
        prometheusMetricsEnabled = false,
        jvmMetricsEnabled = false,
      ),
  )

fun initPersistence(
  persistence: Persistence,
  nodeKeyData: PrivateKeyGenerator.KeyData,
) {
  if (!Files.exists(persistence.dataPath)) Files.createDirectory(persistence.dataPath)
  if (!Files.exists(persistence.privateKeyPath.parent)) Files.createDirectory(persistence.privateKeyPath.parent)
  Files.writeString(persistence.privateKeyPath, nodeKeyData.prefixedPrivateKey.encodeHex())
}

internal fun setQbftConfigIfSequencer(
  config: MaruConfig,
  isSequencer: Boolean,
  nodeKeyData: PrivateKeyGenerator.KeyData,
): MaruConfig {
  var newConfig = config
  if (isSequencer) {
    newConfig =
      config.copy(
        qbft =
          config.qbft
            ?.copy(feeRecipient = nodeKeyData.address)
            ?: QbftConfig(feeRecipient = nodeKeyData.address),
      )
  }
  return newConfig
}

internal fun setP2pConfig(
  config: MaruConfig,
  bootnodes: List<String> = emptyList(),
  staticpeers: List<String> = emptyList(),
): MaruConfig {
  var p2pConfig = config.p2p
  if (bootnodes.isNotEmpty() && staticpeers.isNotEmpty()) {
    p2pConfig = config.p2p ?: P2PConfig(port = 0u)
  }
  if (bootnodes.isNotEmpty()) {
    val updatedDiscovery =
      p2pConfig!!
        .discovery
        ?.copy(bootnodes = bootnodes)
        ?: Discovery(
          refreshInterval = 1.seconds,
          bootnodes = bootnodes,
        )
    p2pConfig = p2pConfig.copy(discovery = updatedDiscovery)
  }
  if (staticpeers.isNotEmpty()) {
    p2pConfig =
      p2pConfig?.copy(
        staticPeers = staticpeers,
      )
  }
  return config.copy(p2p = p2pConfig)
}

internal fun setValidatorConfig(
  config: MaruConfig,
  elNode: ElNode?,
): MaruConfig {
  val validatorConfig =
    elNode
      ?.let {
        config.validatorElNode.copy(
          ethApiEndpoint =
            config.validatorElNode.ethApiEndpoint.copy(
              endpoint = URI.create(it.ethApiUrl()).toURL(),
            ),
          engineApiEndpoint =
            config.validatorElNode.engineApiEndpoint.copy(
              endpoint = URI.create(it.engineApiUrl()).toURL(),
            ),
        )
      } ?: throw IllegalArgumentException("Maru still depends on a El Node")
  return config.copy(validatorElNode = validatorConfig)
}
