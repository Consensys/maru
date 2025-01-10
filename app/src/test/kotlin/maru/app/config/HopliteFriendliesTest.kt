package maru.app.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.Secret
import com.sksamuel.hoplite.json.JsonPropertySource
import com.sksamuel.hoplite.toml.TomlPropertySource
import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalHoplite::class)
class HopliteFriendliesTest {
  private inline fun <reified T : Any> parseJsonConfig(json: String): T {
    return ConfigLoaderBuilder.default()
      .withExplicitSealedTypes()
      .addSource(JsonPropertySource(json))
      .build()
      .loadConfigOrThrow<T>()
  }

  private inline fun <reified T : Any> parseTomlConfig(toml: String): T {
    return ConfigLoaderBuilder.default()
      .withExplicitSealedTypes()
      .addSource(TomlPropertySource(toml))
      .build()
      .loadConfigOrThrow<T>()
  }

  @Test
  fun genesisFileIsParseable() {
    val config =
      parseJsonConfig<BeaconGenesisConfig>(
        """
        {
          "blockTimeMillis": 1000
        }
        """
          .trimIndent(),
      )
    assertThat(config.blockTimeMillis).isEqualTo(1000u)
  }

  @Test
  fun appConfigFileIsParseable() {
    val config =
      parseTomlConfig<MaruConfigDtoToml>(
        """
        [execution-client]
        endpoint = "https://localhost"

        [p2p-config]
        port = 3322

        [validator]
        validator-key = "0xdead"
        """
          .trimIndent(),
      )
    assertThat(config)
      .isEqualTo(
        MaruConfigDtoToml(
          executionClient =
            ExecutionClientConfig(endpoint = URI.create("https://localhost").toURL()),
          p2pConfig = P2P(port = 3322u),
          validator = ValidatorToml(validatorKey = Secret("0xdead")),
        ),
      )
  }
}
