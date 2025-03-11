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
import java.nio.file.Files
import maru.app.MaruApp
import maru.app.MaruAppCli.Companion.loadConfig
import maru.config.MaruConfigDtoToml
import maru.consensus.config.JsonFriendlyForksSchedule

object MaruFactory {
  fun buildTestMaru(
    parisTime: Long,
    pragueTime: Long,
  ): MaruApp {
    val maruConfigResource = this::class.java.getResource("/config/maru.toml")
    val maruConfig = loadConfig<MaruConfigDtoToml>(listOf(File(maruConfigResource!!.path)))
    val consensusGenesisTemplate =
      this::class.java
        .getResource("/config/clique-to-paris-to-prague.template")!!
        .readText()
    val tmpDirFile = Files.createTempDirectory("maru-clique-to-pos").toFile()
    tmpDirFile.deleteOnExit()
    val maruGenesisFile = File(tmpDirFile, "clique-to-paris-to-prague.json")
    maruGenesisFile.writeText(renderTemplate(consensusGenesisTemplate, parisTime, pragueTime))

    val beaconGenesisConfig =
      loadConfig<JsonFriendlyForksSchedule>(listOf(maruGenesisFile))

    return MaruApp(maruConfig.getUnsafe().domainFriendly(), beaconGenesisConfig.getUnsafe().domainFriendly())
  }

  private fun renderTemplate(
    template: String,
    parisTime: Long,
    pragueTime: Long,
  ): String = template.replace("%PARIS_TIME%", parisTime.toString()).replace("%PRAGUE_TIME%", pragueTime.toString())
}
