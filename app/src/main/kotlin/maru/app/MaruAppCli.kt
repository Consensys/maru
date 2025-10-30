/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.io.File
import java.util.concurrent.Callable
import maru.config.MaruConfigLoader
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.Configurator
import picocli.CommandLine
import picocli.CommandLine.Command

internal class KebabToEnumConverter<T : Enum<T>>(
  private val enumClass: Class<T>,
) : CommandLine.ITypeConverter<T> {
  override fun convert(value: String): T {
    // Convert kebab-case to upper snake-case
    val enumName = value.replace('-', '_').uppercase()
    return try {
      java.lang.Enum.valueOf(enumClass, enumName)
    } catch (_: IllegalArgumentException) {
      val validOptions = enumClass.enumConstants.joinToString(", ") { it.name.lowercase().replace('_', '-') }
      throw IllegalArgumentException(
        "Invalid enum value \"$value\". Expected one of: $validOptions",
      )
    }
  }
}

@Command(
  name = "maru",
  showDefaultValues = true,
  abbreviateSynopsis = true,
  description = ["Runs Maru consensus client"],
  version = ["0.0.1"],
  synopsisHeading = "%n",
  descriptionHeading = "%nDescription:%n%n",
  optionListHeading = "%nOptions:%n",
  footerHeading = "%n",
)
class MaruAppCli : Callable<Int> {
  @CommandLine.Option(
    names = ["--config"],
    paramLabel = "CONFIG.toml,CONFIG.overrides.toml",
    description = ["Configuration files"],
    arity = "1..*",
    required = true,
  )
  lateinit var configFiles: List<File>

  @CommandLine.ArgGroup(multiplicity = "1", exclusive = true, validate = true)
  lateinit var genesisOptions: GenesisOptions

  class GenesisOptions(
    @CommandLine.Option(
      names = ["--maru-genesis-file"],
      paramLabel = "BEACON_GENESIS.json",
      description = ["Beacon chain genesis file"],
      required = false,
    )
    var genesisFile: File? = null,
    @CommandLine.Option(
      names = ["--network"],
      paramLabel = "linea-mainnet|linea-sepolia (case-insensitive)",
      description = ["Connects to Linea mainnet or sepolia"],
      required = false,
    )
    val network: Network? = null,
  )

  enum class Network {
    LINEA_MAINNET,
    LINEA_SEPOLIA,
  }

  override fun call(): Int {
    for (configFile in configFiles) {
      if (!validateConfigFile(configFile)) {
        System.err.println("Failed to read config file: \"${configFile.path}\"")
        return 1
      }
    }
    val parsedAppConfig = MaruConfigLoader.loadAppConfigs(configFiles)

    if (genesisOptions.genesisFile != null) {
      if (!validateConfigFile(genesisOptions.genesisFile!!)) {
        System.err.println("Failed to read genesis file file: \"${genesisOptions.genesisFile!!.path}\"")
        return 1
      }
      println("Using the given genesis file from \"${genesisOptions.genesisFile!!.path}\"")
    } else {
      println("Using the genesis file of the named network ${genesisOptions.network}")
      genesisOptions.genesisFile =
        when (genesisOptions.network!!) {
          Network.LINEA_MAINNET -> File("/beacon-genesis-files/linea-mainnet-genesis.json")
          Network.LINEA_SEPOLIA -> File("/beacon-genesis-files/linea-sepolia-genesis.json")
        }
    }
    val parsedBeaconGenesisConfig = MaruConfigLoader.loadGenesisConfig(genesisOptions.genesisFile!!)

    val app =
      MaruAppFactory()
        .create(
          config = parsedAppConfig.domainFriendly(),
          beaconGenesisConfig = parsedBeaconGenesisConfig.domainFriendly(),
        )
    app.start()

    Runtime
      .getRuntime()
      .addShutdownHook(
        Thread {
          app.stop()
          if (LogManager.getContext() is LoggerContext) {
            // Disable log4j auto shutdown hook is not used otherwise
            // Messages in App.stop won't appear in the logs
            Configurator.shutdown(LogManager.getContext() as LoggerContext)
          }
        },
      )

    return 0
  }

  private fun validateConfigFile(file: File): Boolean = file.canRead()
}
