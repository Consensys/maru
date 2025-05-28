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
import java.nio.file.Files
import java.nio.file.Path
import maru.app.MaruApp
import maru.app.MaruAppCli.Companion.loadConfig
import maru.config.MaruConfigDtoToml
import maru.config.Utils
import maru.consensus.ElFork
import maru.consensus.config.JsonFriendlyForksSchedule
import maru.p2p.NoOpP2PNetwork
import maru.p2p.P2PNetwork

object MaruFactory {
  private val consensusConfigDir = "/e2e/config"
  private val pragueConsensusConfig = "$consensusConfigDir/qbft-prague.json"
  const val VALIDATOR_PRIVATE_KEY = "1dd171cec7e2995408b5513004e8207fe88d6820aeff0d82463b3e41df251aae"
  const val VALIDATOR_PRIVATE_KEY_WITH_PREFIX = "0x08021220$VALIDATOR_PRIVATE_KEY"
  const val VALIDATOR_ADDRESS = "0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0"
  const val VALIDATOR_NODE_ID = "16Uiu2HAmFjVuJoKD6sobrxwyJyysM1rgCsfWKzFLwvdB2HKuHwTg"

  private fun buildMaruValidatorConfigStringWithoutP2P(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataPath: String,
  ): String =
    """
    [persistence]
    data-path="$dataPath"

    [qbft-options]

    [payloadValidator]
    engine-api-endpoint = { endpoint = "$engineApiRpc" }
    eth-api-endpoint = { endpoint = "$ethereumJsonRpcUrl" }
    """.trimIndent()

  private fun buildMaruValidatorConfigStringWithP2P(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataPath: String,
    validatorP2pPort: UInt = 0u,
  ): String =
    """
    [persistence]
    data-path="$dataPath"

    [p2p-config]
    port = $validatorP2pPort
    ip-address = "127.0.0.1"
    static-peers = []

    [qbft-options]

    [payloadValidator]
    engine-api-endpoint = { endpoint = "$engineApiRpc" }
    eth-api-endpoint = { endpoint = "$ethereumJsonRpcUrl" }
    """.trimIndent()

  private fun buildMaruFollowerConfigStringWithP2pNetwork(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataPath: String,
    validatorP2pPort: UInt,
  ): String =
    """
    [persistence]
    data-path="$dataPath"

    [p2p-config]
    port = 0
    ip-address = "127.0.0.1"
    static-peers = ["/ip4/127.0.0.1/tcp/$validatorP2pPort/p2p/$VALIDATOR_NODE_ID"]

    [payload-validator]
    engine-api-endpoint = { endpoint = "$engineApiRpc" }
    eth-api-endpoint = { endpoint = "$ethereumJsonRpcUrl" }

    [follower-engine-apis]
    follower1 = { endpoint = "$engineApiRpc" }
    """.trimIndent()

  private fun buildMaruFollowerConfigStringWithoutP2pNetwork(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataPath: String,
  ): String =
    """
    [persistence]
    data-path="$dataPath"

    [payload-validator]
    engine-api-endpoint = { endpoint = "$engineApiRpc" }
    eth-api-endpoint = { endpoint = "$ethereumJsonRpcUrl" }

    [follower-engine-apis]
    follower1 = { endpoint = "$engineApiRpc" }
    """.trimIndent()

  private fun pickConsensusConfig(elFork: ElFork): String =
    when (elFork) {
      ElFork.Prague -> pragueConsensusConfig
    }

  fun buildTestMaruValidatorWithoutP2p(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    elFork: ElFork,
    dataDir: Path,
    p2pNetwork: P2PNetwork = NoOpP2PNetwork,
  ): MaruApp {
    val appConfig =
      Utils.parseTomlConfig<MaruConfigDtoToml>(
        buildMaruValidatorConfigStringWithoutP2P(
          ethereumJsonRpcUrl = ethereumJsonRpcUrl,
          engineApiRpc = engineApiRpc,
          dataPath = dataDir.toString(),
        ),
      )
    Files.writeString(appConfig.domainFriendly().persistence.privateKeyPath, VALIDATOR_PRIVATE_KEY_WITH_PREFIX)

    val consensusGenesisResource = this::class.java.getResource(pickConsensusConfig(elFork))
    val beaconGenesisConfig = loadConfig<JsonFriendlyForksSchedule>(listOf(File(consensusGenesisResource!!.path)))

    return MaruApp(
      config = appConfig.domainFriendly(),
      beaconGenesisConfig = beaconGenesisConfig.getUnsafe().domainFriendly(),
      p2pNetwork = p2pNetwork,
    )
  }

  fun buildTestMaruValidatorWithP2p(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    elFork: ElFork,
    dataDir: Path,
    p2pNetwork: P2PNetwork = NoOpP2PNetwork,
    validatorP2pPort: UInt,
  ): MaruApp {
    val appConfig =
      Utils.parseTomlConfig<MaruConfigDtoToml>(
        buildMaruValidatorConfigStringWithP2P(
          ethereumJsonRpcUrl = ethereumJsonRpcUrl,
          engineApiRpc = engineApiRpc,
          dataPath = dataDir.toString(),
          validatorP2pPort = validatorP2pPort,
        ),
      )
    Files.writeString(appConfig.domainFriendly().persistence.privateKeyPath, VALIDATOR_PRIVATE_KEY_WITH_PREFIX)

    val consensusGenesisResource = this::class.java.getResource(pickConsensusConfig(elFork))
    val beaconGenesisConfig = loadConfig<JsonFriendlyForksSchedule>(listOf(File(consensusGenesisResource!!.path)))

    return MaruApp(
      config = appConfig.domainFriendly(),
      beaconGenesisConfig = beaconGenesisConfig.getUnsafe().domainFriendly(),
      p2pNetwork = p2pNetwork,
    )
  }

  fun buildTestMaruFollowerWithP2pNetwork(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    elFork: ElFork,
    dataDir: Path,
    validatorP2pPort: UInt = 0u,
    p2pNetwork: P2PNetwork = NoOpP2PNetwork,
  ): MaruApp {
    val appConfig =
      Utils.parseTomlConfig<MaruConfigDtoToml>(
        buildMaruFollowerConfigStringWithP2pNetwork(
          ethereumJsonRpcUrl = ethereumJsonRpcUrl,
          engineApiRpc = engineApiRpc,
          dataPath = dataDir.toString(),
          validatorP2pPort = validatorP2pPort,
        ),
      )
    val consensusGenesisResource = this::class.java.getResource(pickConsensusConfig(elFork))
    val beaconGenesisConfig = loadConfig<JsonFriendlyForksSchedule>(listOf(File(consensusGenesisResource!!.path)))

    return MaruApp(
      config = appConfig.domainFriendly(),
      beaconGenesisConfig = beaconGenesisConfig.getUnsafe().domainFriendly(),
      p2pNetwork = p2pNetwork,
    )
  }

  fun buildTestMaruFollowerWithoutP2pNetwork(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    elFork: ElFork,
    dataDir: Path,
    p2pNetwork: P2PNetwork = NoOpP2PNetwork,
  ): MaruApp {
    val appConfig =
      Utils.parseTomlConfig<MaruConfigDtoToml>(
        buildMaruFollowerConfigStringWithoutP2pNetwork(
          ethereumJsonRpcUrl = ethereumJsonRpcUrl,
          engineApiRpc = engineApiRpc,
          dataPath = dataDir.toString(),
        ),
      )
    val consensusGenesisResource = this::class.java.getResource(pickConsensusConfig(elFork))
    val beaconGenesisConfig = loadConfig<JsonFriendlyForksSchedule>(listOf(File(consensusGenesisResource!!.path)))

    return MaruApp(
      config = appConfig.domainFriendly(),
      beaconGenesisConfig = beaconGenesisConfig.getUnsafe().domainFriendly(),
      p2pNetwork = p2pNetwork,
    )
  }

  fun buildTestMaruValidatorWithConsensusSwitch(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataDir: Path,
    switchTimestamp: Long,
    p2pNetwork: P2PNetwork = NoOpP2PNetwork,
  ): MaruApp {
    val appConfig =
      Utils.parseTomlConfig<MaruConfigDtoToml>(
        buildMaruValidatorConfigStringWithoutP2P(
          ethereumJsonRpcUrl = ethereumJsonRpcUrl,
          engineApiRpc = engineApiRpc,
          dataPath = dataDir.toString(),
        ),
      )
    Files.writeString(appConfig.domainFriendly().persistence.privateKeyPath, VALIDATOR_PRIVATE_KEY_WITH_PREFIX)

    // Build the genesis file string directly
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
            "validatorSet": ["0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0"],
            "blockTimeSeconds": 1,
            "feeRecipient": "$VALIDATOR_ADDRESS",
            "elFork": "Prague"
          }
        }
      }
      """.trimIndent()

    val tempFile = Files.createTempFile("clique-to-qbft", ".json").toFile()
    tempFile.deleteOnExit()
    tempFile.writeText(genesisContent)

    val beaconGenesisConfig = loadConfig<JsonFriendlyForksSchedule>(listOf(tempFile))

    return MaruApp(
      config = appConfig.domainFriendly(),
      beaconGenesisConfig = beaconGenesisConfig.getUnsafe().domainFriendly(),
      p2pNetwork = p2pNetwork,
    )
  }
}
