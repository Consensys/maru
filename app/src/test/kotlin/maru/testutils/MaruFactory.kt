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
import maru.app.MaruApp
import maru.app.MaruAppCli.Companion.loadConfig
import maru.app.config.JsonFriendlyForksSchedule
import maru.app.config.MaruConfigDtoToml
import maru.app.config.Utils

object MaruFactory {
  private fun buildMaruConfigString(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    networks: List<String>,
    staticPeersList: List<String>,
    privateKeyFile: String,
  ): String =
    """
    [execution-client]
    ethereum-json-rpc-endpoint = "$ethereumJsonRpcUrl"
    engine-api-json-rpc-endpoint = "$engineApiRpc"
    min-time-between-get-payload-attempts=800m

    [dummy-consensus-options]
    communication-time-margin=100m

    [p2p-config]
    networks = ${networks.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}
    staticPeers = ${staticPeersList.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}
    private-key-file = "$privateKeyFile"
    gossip-topics = ["testing"]

    [validator]
    validator-key = "0xdead"
    """.trimIndent()

  fun buildTestMaru(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    networks: List<String>,
    staticPeers: List<String>,
    privateKeyFile: String,
  ): MaruApp {
    val appConfig =
      Utils.parseTomlConfig<MaruConfigDtoToml>(
        buildMaruConfigString(
          ethereumJsonRpcUrl = ethereumJsonRpcUrl,
          engineApiRpc = engineApiRpc,
          networks = networks,
          staticPeersList = staticPeers,
          privateKeyFile = privateKeyFile,
        ),
      )
    val consensusGenesisResource = this::class.java.getResource("/e2e/config/dummy-consensus.json")
    val beaconGenesisConfig = loadConfig<JsonFriendlyForksSchedule>(listOf(File(consensusGenesisResource!!.path)))

    return MaruApp(appConfig.domainFriendly(), beaconGenesisConfig.getUnsafe().domainFriendly())
  }
}
