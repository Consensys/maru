package maru.app

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addFileSource
import java.io.File
import java.util.concurrent.Callable
import maru.app.config.BeaconGenesisConfig
import maru.app.config.MaruConfigDtoToml
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.Configurator
import picocli.CommandLine
import picocli.CommandLine.Command

@Command(
  name = "maru",
  showDefaultValues = true,
  abbreviateSynopsis = true,
  description = ["Runs Linea Coordinator"],
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
  )
  private val configFiles: List<File>? = null

  @CommandLine.Option(
    names = ["--besu-genesis-file"],
    paramLabel = "BEACON_GENESIS.json",
    description = ["Beacon chain genesis file"],
  )
  private val genesisFile: File? = null

  override fun call(): Int {
    for (configFile in configFiles!!) {
      if (!validateConfigFile(configFile)) {
        System.err.println("Failed to read config file: ${configFile.absolutePath}")
        return 1
      }
    }
    if (!validateConfigFile(genesisFile!!)) {
      System.err.println("Failed to read genesis file file: ${genesisFile.absolutePath}")
      return 1
    }
    val appConfig = loadConfig<MaruConfigDtoToml>(configFiles)
    val beaconGenesisConfig = loadConfig<BeaconGenesisConfig>(listOf(genesisFile))

    if (!validateParsedFile(appConfig, configFiles.map { it.absolutePath }.toString())) {
      return 1
    }

    if (!validateParsedFile(beaconGenesisConfig, genesisFile.absolutePath)) {
      return 1
    }

    val parsedAppConfig = appConfig.getUnsafe()
    val parsedBeaconGenesisConfig = beaconGenesisConfig.getUnsafe()

    val app = MaruApp(parsedAppConfig.reified(), parsedBeaconGenesisConfig)
    app.start()

    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          app.stop()
          if (LogManager.getContext() is LoggerContext) {
            // Disable log4j auto shutdown hook is not used otherwise
            // Messages in App.stop won't appear in the logs
            Configurator.shutdown(LogManager.getContext() as LoggerContext)
          }
        }
      )

    return 0
  }

  @OptIn(ExperimentalHoplite::class)
  private inline fun <reified T : Any> loadConfig(configFiles: List<File>): ConfigResult<T> {
    val confBuilder: ConfigLoaderBuilder =
      ConfigLoaderBuilder.Companion.empty().addDefaults().withExplicitSealedTypes()
    for (configFile in configFiles.reversed()) {
      // files must be added in reverse order for overriding
      confBuilder.addFileSource(configFile, false)
    }

    return confBuilder.build().loadConfig<T>(emptyList())
  }

  private fun validateConfigFile(file: File): Boolean {
    return file.canRead()
  }

  private fun validateParsedFile(configResult: ConfigResult<*>, validatedFile: String): Boolean {
    if (configResult.isInvalid()) {
      System.err.println(
        "Invalid config file: $validatedFile, ${configResult.getInvalidUnsafe().description()}"
      )
      return false
    }
    return true
  }
}
