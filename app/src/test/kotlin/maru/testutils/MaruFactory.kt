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
import java.nio.file.Path
import maru.app.MaruApp
import maru.app.MaruAppCli.Companion.loadConfig
import maru.config.MaruConfigDtoToml
import maru.config.Utils
import maru.consensus.ElFork
import maru.consensus.config.JsonFriendlyForksSchedule

object MaruFactory {
  private val consensusConfigDir = "/e2e/config"
  private val pragueConsensusConfig = "$consensusConfigDir/qbft-prague.json"

  private fun buildMaruConfigString(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    dataPath: String,
    networks: List<String>,
    staticPeersList: List<String>,
    privateKeyFile: String,
  ): String =
    """
    [persistence]
    data-path="$dataPath"

    [sot-eth-endpoint]
    endpoint = "$ethereumJsonRpcUrl"

    [qbft-options]
    communication-margin=200m

    [p2p-config]
    networks = ${networks.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}
    staticPeers = ${staticPeersList.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}
    private-key-file = "$privateKeyFile"
    gossip-topics = ["testing"] // TODO: not used

    [validator]
    private-key = "0x1dd171cec7e2995408b5513004e8207fe88d6820aeff0d82463b3e41df251aae"
    el-client-engine-api-endpoint = "$engineApiRpc"
    """.trimIndent()

  private fun pickConsensusConfig(elFork: ElFork): String =
    when (elFork) {
      ElFork.Prague -> pragueConsensusConfig
    }

  fun buildTestMaru(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    elFork: ElFork,
    dataDir: Path,networks: List<String>,
    staticPeers: List<String>,
    privateKeyFile: String,
  ): MaruApp {
    val appConfig =
      Utils.parseTomlConfig<MaruConfigDtoToml>(
        buildMaruConfigString(
          ethereumJsonRpcUrl = ethereumJsonRpcUrl,
          engineApiRpc = engineApiRpc,
          dataPath = dataDir.toString(),
          networks = networks,
          staticPeersList = staticPeers,
          privateKeyFile = privateKeyFile,
        ),
      )
    val consensusGenesisResource = this::class.java.getResource(pickConsensusConfig(elFork))
    val beaconGenesisConfig = loadConfig<JsonFriendlyForksSchedule>(listOf(File(consensusGenesisResource!!.path)))

    return MaruApp(appConfig.domainFriendly(), beaconGenesisConfig.getUnsafe().domainFriendly())
  }
}
