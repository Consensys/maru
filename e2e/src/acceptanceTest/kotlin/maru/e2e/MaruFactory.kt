/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.e2e

import java.io.File
import java.nio.file.Files
import maru.app.MaruApp
import maru.app.MaruAppFactory
import maru.config.MaruConfigDtoToml
import maru.config.consensus.JsonFriendlyForksSchedule
import maru.config.loadConfigs

const val VALIDATOR_PRIVATE_KEY_WITH_PREFIX =
  "0x080212201dd171cec7e2995408b5513004e8207fe88d6820aeff0d82463b3e41df251aae"

object MaruFactory {
  fun buildTestMaru(pragueTime: Long): MaruApp {
    val maruConfigResource = this::class.java.getResource("/config/maru.toml")
    val maruConfig = loadConfigs<MaruConfigDtoToml>(listOf(File(maruConfigResource!!.path).toPath()))
    Files.writeString(
      maruConfig
        .domainFriendly()
        .persistence.privateKeyPath,
      VALIDATOR_PRIVATE_KEY_WITH_PREFIX,
    )
    val consensusGenesisTemplate =
      this::class.java
        .getResource("/config/clique-to-prague.template")!!
        .readText()
    val tmpDirFile = Files.createTempDirectory("maru-clique-to-pos").toFile()
    tmpDirFile.deleteOnExit()
    val maruGenesisFile = File(tmpDirFile, "clique-to-prague.json")
    maruGenesisFile.writeText(renderTemplate(consensusGenesisTemplate, pragueTime))

    val beaconGenesisConfig =
      loadConfigs<JsonFriendlyForksSchedule>(listOf(maruGenesisFile.toPath()))

    return MaruAppFactory().create(
      config = maruConfig.domainFriendly(),
      beaconGenesisConfig = beaconGenesisConfig.domainFriendly(),
    )
  }

  private fun renderTemplate(
    template: String,
    pragueTime: Long,
  ): String = template.replace("%PRAGUE_TIME%", pragueTime.toString())
}
