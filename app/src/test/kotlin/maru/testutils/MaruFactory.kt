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
import maru.config.MaruConfigDtoToml
import maru.config.Utils
import maru.consensus.ElFork
import maru.consensus.config.JsonFriendlyForksSchedule

object MaruFactory {
  private val consensusConfigDir = "/e2e/config"
  private val parisConsensusConfig = "$consensusConfigDir/dummy-consensus-paris.json"
  private val pragueConsensusConfig = "$consensusConfigDir/dummy-consensus-prague.json"

  private fun buildMaruConfigString(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
  ): String =
    """
    [execution-client]
    ethereum-json-rpc-endpoint = "$ethereumJsonRpcUrl"
    engine-api-json-rpc-endpoint = "$engineApiRpc"
    min-time-between-get-payload-attempts=800m

    [dummy-consensus-options]
    communication-time-margin=100m

    [p2p-config]
    port = 3322

    [validator]
    validator-key = "0xdead"
    """.trimIndent()

  private fun pickConsensusConfig(elFork: ElFork): String =
    when (elFork) {
      ElFork.Paris -> parisConsensusConfig
      ElFork.Prague -> pragueConsensusConfig
    }

  fun buildTestMaru(
    ethereumJsonRpcUrl: String,
    engineApiRpc: String,
    elFork: ElFork,
  ): MaruApp {
    val appConfig =
      Utils.parseTomlConfig<MaruConfigDtoToml>(
        buildMaruConfigString(
          ethereumJsonRpcUrl = ethereumJsonRpcUrl,
          engineApiRpc = engineApiRpc,
        ),
      )
    val consensusGenesisResource = this::class.java.getResource(pickConsensusConfig(elFork))
    val beaconGenesisConfig = loadConfig<JsonFriendlyForksSchedule>(listOf(File(consensusGenesisResource!!.path)))

    return MaruApp(appConfig.domainFriendly(), beaconGenesisConfig.getUnsafe().domainFriendly())
  }
}
