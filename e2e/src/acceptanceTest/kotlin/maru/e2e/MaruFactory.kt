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
package maru.e2e

import java.io.File
import maru.app.MaruApp
import maru.app.MaruAppCli.Companion.loadConfig
import maru.app.config.JsonFriendlyForksSchedule
import maru.app.config.MaruConfigDtoToml

object MaruFactory {
  fun buildTestMaru(): MaruApp {
    val maruConfigResource = this::class.java.getResource("/config/maru.toml")
    val maruConfig = loadConfig<MaruConfigDtoToml>(listOf(File(maruConfigResource!!.path)))
    val consensusGenesisResource = this::class.java.getResource("/config/dummy-consensus.json")
    val beaconGenesisConfig = loadConfig<JsonFriendlyForksSchedule>(listOf(File(consensusGenesisResource!!.path)))

    return MaruApp(maruConfig.getUnsafe().domainFriendly(), beaconGenesisConfig.getUnsafe().domainFriendly())
  }
}
