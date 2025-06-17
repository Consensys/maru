/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.testutils

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.crypto.marshalPrivateKey
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import linea.contract.l1.LineaRollupSmartContractClientReadOnly
import maru.app.MaruApp
import maru.app.MaruAppFactory
import maru.config.ApiEndpointConfig
import maru.config.FollowersConfig
import maru.config.LineaConfig
import maru.config.MaruConfig
import maru.config.ObservabilityOptions
import maru.config.P2P
import maru.config.Persistence
import maru.config.QbftOptions
import maru.config.ValidatorElNode
import maru.config.consensus.Utils
import maru.consensus.ForksSchedule
import maru.consensus.state.FinalizationProvider
import maru.crypto.Crypto
import maru.extensions.encodeHex
import maru.extensions.fromHexToByteArray
import maru.p2p.NoOpP2PNetwork
import maru.p2p.P2PNetwork

/**
 * The same MaruFactory should be used per network. Otherwise, validators won't match between Maru instances
 */
class MaruFactory {
  companion object {
    val defaultReconnectDelay = 500.milliseconds
  }

  private val validatorPrivateKeyWithPrefix = generatePrivateKey()
  private val validatorPrivateKeyWithPrefixString = marshalPrivateKey(validatorPrivateKeyWithPrefix).encodeHex()
  private val validatorNodeId = PeerId.fromPubKey(validatorPrivateKeyWithPrefix.publicKey())
  val qbftValidator =
    Crypto.privateKeyToValidator(Crypto.privateKeyBytesWithoutPrefix(validatorPrivateKeyWithPrefix.bytes()))
  val validatorAddress = qbftValidator.address.encodeHex()
  private val genesisFileWithoutSwitch =
    """
    {
      "chainId": 1337,
      "config": {
        "0": {
          "type": "qbft",
          "blockTimeSeconds": 1,
          "validatorSet": ["$validatorAddress"],
          "elFork": "Prague"
        }
      }
    }

    """.trimIndent()
  private val validatorQbftOptions =
    QbftOptions(
      feeRecipient = validatorAddress.fromHexToByteArray(),
      minBlockBuildTime = 200.milliseconds,
    )

  private val beaconGenesisConfig: ForksSchedule =
    run {
      Utils.parseBeaconChainConfig(genesisFileWithoutSwitch).domainFriendly()
    }

  private fun generatePrivateKey(): PrivKey = generateKeyPair(KeyType.SECP256K1).component1()

  private fun buildMaruConfig(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataDir: Path,
    p2pConfig: P2P? = null,
    followers: FollowersConfig = FollowersConfig(emptyMap()),
    qbftOptions: QbftOptions? = null,
    observabilityOptions: ObservabilityOptions =
      ObservabilityOptions(port = 0u, prometheusMetricsEnabled = true, jvmMetricsEnabled = true),
    overridingLineaContractClient: LineaRollupSmartContractClientReadOnly? = null,
  ): MaruConfig {
    val lineaConfig =
      overridingLineaContractClient?.let {
        LineaConfig(
          contractAddress = overridingLineaContractClient.getAddress(),
          l1EthApi = ApiEndpointConfig(URI.create(ethereumJsonRpcUrl).toURL()),
          l1PollingInterval = 100.milliseconds,
        )
      }

    return MaruConfig(
      persistence = Persistence(dataPath = dataDir),
      qbftOptions = qbftOptions,
      validatorElNode =
        ValidatorElNode(
          ethApiEndpoint = ApiEndpointConfig(URI.create(ethereumJsonRpcUrl).toURL()),
          engineApiEndpoint = ApiEndpointConfig(URI.create(engineApiRpc).toURL()),
        ),
      p2pConfig = p2pConfig,
      followers = followers,
      observabilityOptions = observabilityOptions,
      linea = lineaConfig,
    )
  }

  private fun writeValidatorPrivateKey(config: MaruConfig) {
    Files.writeString(config.persistence.privateKeyPath, validatorPrivateKeyWithPrefixString)
  }

  private fun buildApp(
    config: MaruConfig,
    beaconGenesisConfig: ForksSchedule = this.beaconGenesisConfig,
    overridingP2PNetwork: P2PNetwork? = null,
    overridingFinalizationProvider: FinalizationProvider? = null,
    overridingLineaContractClient: LineaRollupSmartContractClientReadOnly? = null,
  ): MaruApp =
    MaruAppFactory().create(
      config = config,
      beaconGenesisConfig = beaconGenesisConfig,
      overridingP2PNetwork = overridingP2PNetwork,
      overridingFinalizationProvider = overridingFinalizationProvider,
      overridingLineaContractClient = overridingLineaContractClient,
    )

  private fun buildP2pConfig(
    p2pPort: UInt = 0u,
    validatorPortForStaticPeering: UInt? = null,
  ): P2P {
    val staticPeers =
      if (validatorPortForStaticPeering != null) {
        val validatorPeer = "/ip4/127.0.0.1/tcp/$validatorPortForStaticPeering/p2p/$validatorNodeId"
        listOf(validatorPeer)
      } else {
        emptyList()
      }
    return P2P("127.0.0.1", port = p2pPort, staticPeers = staticPeers, reconnectDelay = defaultReconnectDelay)
  }

  private fun buildFollowersConfig(engineApiRpc: String): FollowersConfig =
    FollowersConfig(mapOf("validator-el-node" to ApiEndpointConfig(URI.create(engineApiRpc).toURL())))

  fun buildTestMaruValidatorWithoutP2pPeering(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataDir: Path,
    overridingP2PNetwork: P2PNetwork? = null,
  ): MaruApp {
    val config =
      buildMaruConfig(
        ethereumJsonRpcUrl = ethereumJsonRpcUrl,
        engineApiRpc = engineApiRpc,
        dataDir = dataDir,
        qbftOptions = validatorQbftOptions,
      )
    writeValidatorPrivateKey(config)
    return buildApp(config, overridingP2PNetwork = overridingP2PNetwork)
  }

  fun buildTestMaruValidatorWithP2pPeering(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataDir: Path,
    overridingP2PNetwork: P2PNetwork? = null,
    overridingFinalizationProvider: FinalizationProvider? = null,
    overridingLineaContractClient: LineaRollupSmartContractClientReadOnly? = null,
    p2pPort: UInt = 0u,
  ): MaruApp {
    val p2pConfig = buildP2pConfig(p2pPort = p2pPort, validatorPortForStaticPeering = null)
    val config =
      buildMaruConfig(
        ethereumJsonRpcUrl = ethereumJsonRpcUrl,
        engineApiRpc = engineApiRpc,
        dataDir = dataDir,
        p2pConfig = p2pConfig,
        followers = FollowersConfig(emptyMap()),
        qbftOptions = validatorQbftOptions,
        overridingLineaContractClient = overridingLineaContractClient,
      )
    writeValidatorPrivateKey(config)

    return buildApp(
      config = config,
      overridingP2PNetwork = overridingP2PNetwork,
      overridingFinalizationProvider = overridingFinalizationProvider,
      overridingLineaContractClient = overridingLineaContractClient,
    )
  }

  fun buildTestMaruFollowerWithP2pPeering(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataDir: Path,
    validatorPortForStaticPeering: UInt?,
    overridingFinalizationProvider: FinalizationProvider? = null,
    overridingLineaContractClient: LineaRollupSmartContractClientReadOnly? = null,
  ): MaruApp {
    val p2pConfig = buildP2pConfig(validatorPortForStaticPeering = validatorPortForStaticPeering)
    val followers = buildFollowersConfig(engineApiRpc)
    val config =
      buildMaruConfig(
        ethereumJsonRpcUrl = ethereumJsonRpcUrl,
        engineApiRpc = engineApiRpc,
        dataDir = dataDir,
        p2pConfig = p2pConfig,
        followers = followers,
        overridingLineaContractClient = overridingLineaContractClient,
      )
    return buildApp(
      config,
      overridingFinalizationProvider = overridingFinalizationProvider,
      overridingLineaContractClient = overridingLineaContractClient,
    )
  }

  fun buildTestMaruFollowerWithoutP2pPeering(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataDir: Path,
    p2pNetwork: P2PNetwork = NoOpP2PNetwork,
  ): MaruApp {
    val followers = buildFollowersConfig(engineApiRpc)
    val config =
      buildMaruConfig(
        ethereumJsonRpcUrl = ethereumJsonRpcUrl,
        engineApiRpc = engineApiRpc,
        dataDir = dataDir,
        followers = followers,
      )
    return buildApp(config, overridingP2PNetwork = p2pNetwork)
  }

  fun buildTestMaruValidatorWithConsensusSwitch(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataDir: Path,
    switchTimestamp: Long,
    p2pNetwork: P2PNetwork = NoOpP2PNetwork,
  ): MaruApp {
    val config =
      buildMaruConfig(
        ethereumJsonRpcUrl = ethereumJsonRpcUrl,
        engineApiRpc = engineApiRpc,
        dataDir = dataDir,
        qbftOptions = validatorQbftOptions,
      )
    writeValidatorPrivateKey(config)
    val genesisContent =
      """
      {
        "chainId": 1337,
        "config": {
          "0": {
            "type": "delegated",
            "blockTimeSeconds": 1
          },
          "$switchTimestamp": {
            "type": "qbft",
            "blockTimeSeconds": 1,
            "validatorSet": ["$validatorAddress"],
            "feeRecipient": "$validatorAddress",
            "elFork": "Prague"
          }
        }
      }
      """.trimIndent()
    val beaconGenesisConfig = Utils.parseBeaconChainConfig(genesisContent).domainFriendly()
    return buildApp(config, beaconGenesisConfig = beaconGenesisConfig, overridingP2PNetwork = p2pNetwork)
  }
}
