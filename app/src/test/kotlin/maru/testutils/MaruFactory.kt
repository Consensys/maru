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
package maru.testutils

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import maru.app.MaruApp
import maru.app.MaruAppCli.Companion.loadConfig
import maru.config.ApiEndpointConfig
import maru.config.FollowersConfig
import maru.config.MaruConfig
import maru.config.P2P
import maru.config.Persistence
import maru.config.QbftOptions
import maru.config.ValidatorElNode
import maru.consensus.ForksSchedule
import maru.consensus.config.JsonFriendlyForksSchedule
import maru.consensus.config.Utils
import maru.p2p.NoOpP2PNetwork
import maru.p2p.P2PNetwork

object MaruFactory {
  private const val CONSENSUS_CONFIG_DIR = "/e2e/config"
  val defaultReconnectDelay = 500.milliseconds
  private const val PRAGUE_CONSENSUS_CONFIG_PATH = "$CONSENSUS_CONFIG_DIR/qbft-prague.json"
  const val VALIDATOR_PRIVATE_KEY = "1dd171cec7e2995408b5513004e8207fe88d6820aeff0d82463b3e41df251aae"
  const val VALIDATOR_PRIVATE_KEY_WITH_PREFIX = "0x08021220$VALIDATOR_PRIVATE_KEY"
  const val VALIDATOR_ADDRESS = "0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0"
  const val VALIDATOR_NODE_ID = "16Uiu2HAmFjVuJoKD6sobrxwyJyysM1rgCsfWKzFLwvdB2HKuHwTg"

  private val beaconGenesisConfig: ForksSchedule =
    run {
      val consensusGenesisResource = this::class.java.getResource(PRAGUE_CONSENSUS_CONFIG_PATH)
      loadConfig<JsonFriendlyForksSchedule>(listOf(File(consensusGenesisResource!!.path))).getUnsafe().domainFriendly()
    }

  private fun buildMaruConfig(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataDir: Path,
    p2pConfig: P2P? = null,
    followers: FollowersConfig = FollowersConfig(emptyMap()),
    qbftOptions: QbftOptions? = null,
  ): MaruConfig =
    MaruConfig(
      Persistence(dataPath = dataDir),
      qbftOptions = qbftOptions,
      validatorElNode =
        ValidatorElNode(
          ethApiEndpoint = ApiEndpointConfig(URI.create(ethereumJsonRpcUrl).toURL()),
          engineApiEndpoint = ApiEndpointConfig(URI.create(engineApiRpc).toURL()),
        ),
      p2pConfig = p2pConfig,
      followers = followers,
    )

  private fun writeValidatorPrivateKey(config: MaruConfig) {
    Files.writeString(config.persistence.privateKeyPath, VALIDATOR_PRIVATE_KEY_WITH_PREFIX)
  }

  private fun buildApp(
    config: MaruConfig,
    beaconGenesisConfig: ForksSchedule = this.beaconGenesisConfig,
    p2pNetwork: P2PNetwork = NoOpP2PNetwork,
  ): MaruApp = MaruApp(config = config, beaconGenesisConfig = beaconGenesisConfig, p2pNetwork = p2pNetwork)

  private fun buildP2pConfig(
    p2pPort: UInt = 0u,
    validatorPortForStaticPeering: UInt? = null,
  ): P2P {
    val staticPeers =
      if (validatorPortForStaticPeering != null) {
        val validatorPeer = "/ip4/127.0.0.1/tcp/$validatorPortForStaticPeering/p2p/$VALIDATOR_NODE_ID"
        listOf(validatorPeer)
      } else {
        emptyList()
      }
    return P2P("127.0.0.1", port = p2pPort, staticPeers = staticPeers, reconnectDelay = defaultReconnectDelay)
  }

  private fun buildFollowersConfig(engineApiRpc: String): FollowersConfig =
    FollowersConfig(mapOf("validator-el-node" to ApiEndpointConfig(URI.create(engineApiRpc).toURL())))

  fun buildTestMaruValidatorWithoutP2p(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataDir: Path,
    p2pNetwork: P2PNetwork = NoOpP2PNetwork,
  ): MaruApp {
    val config =
      buildMaruConfig(
        ethereumJsonRpcUrl = ethereumJsonRpcUrl,
        engineApiRpc = engineApiRpc,
        dataDir = dataDir,
        qbftOptions = QbftOptions(),
      )
    writeValidatorPrivateKey(config)
    return buildApp(config, p2pNetwork = p2pNetwork)
  }

  fun buildTestMaruValidatorWithP2p(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataDir: Path,
    p2pNetwork: P2PNetwork = NoOpP2PNetwork,
    p2pPort: UInt = 0u,
  ): MaruApp {
    val p2pConfig = buildP2pConfig(p2pPort = p2pPort)
    val config =
      buildMaruConfig(
        ethereumJsonRpcUrl = ethereumJsonRpcUrl,
        engineApiRpc = engineApiRpc,
        dataDir = dataDir,
        p2pConfig = p2pConfig,
        followers = FollowersConfig(emptyMap()),
        qbftOptions = QbftOptions(),
      )
    writeValidatorPrivateKey(config)
    return buildApp(config, p2pNetwork = p2pNetwork)
  }

  fun buildTestMaruFollowerWithP2pNetwork(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataDir: Path,
    validatorPortForStaticPeering: UInt?,
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
      )
    return buildApp(config)
  }

  fun buildTestMaruFollowerWithoutP2pNetwork(
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
    return buildApp(config, p2pNetwork = p2pNetwork)
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
        qbftOptions = QbftOptions(),
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
            "validatorSet": ["$VALIDATOR_ADDRESS"],
            "blockTimeSeconds": 1,
            "feeRecipient": "$VALIDATOR_ADDRESS",
            "elFork": "Prague"
          }
        }
      }
      """.trimIndent()
    val beaconGenesisConfig = Utils.parseBeaconChainConfig(genesisContent).domainFriendly()
    return buildApp(config, beaconGenesisConfig = beaconGenesisConfig, p2pNetwork = p2pNetwork)
  }
}
