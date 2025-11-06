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
import maru.config.MaruConfigDtoToml
import maru.config.MaruConfigLoader.parseBeaconChainConfig
import maru.config.MaruConfigLoader.parseConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import picocli.CommandLine

class MaruAppCliTest {
  companion object {
    object NoOpMaruApp : LongRunningCloseable {
      override fun start() = Unit

      override fun stop() = Unit

      override fun close() = Unit
    }

    private val maruConfigDtoToml =
      """
      [persistence]
      data-path="/tmp/maru-db"

      [observability]
      port = 9090
      jvm-metrics-enabled = true
      prometheus-metrics-enabled = true

      [api]
      port = 8080

      [payload-validator]
      engine-api-endpoint = { endpoint = "http://localhost:8550" }
      eth-api-endpoint = { endpoint = "http://localhost:8545" }

      [syncing]
      peer-chain-height-polling-interval = "5 seconds"
      sync-target-selection = "Highest"
      el-sync-status-refresh-interval = "5 seconds"
      """.trimIndent()

    private val maruConfigOverridesDtoToml =
      """
      [persistence]
      data-path="./OVERRIDE/maru-db"
      private-key-path="./OVERRIDE/maru-db/private-key"

      [payload-validator]
      engine-api-endpoint = { endpoint = "http://OVEERRIDE:8550" }

      [syncing]
      peer-chain-height-polling-interval = "10 seconds"
      """.trimIndent()

    private val expectedMaruConfigDtoToml =
      """
      [persistence]
      data-path="./OVERRIDE/maru-db"
      private-key-path="./OVERRIDE/maru-db/private-key"

      [observability]
      port = 9090
      jvm-metrics-enabled = true
      prometheus-metrics-enabled = true

      [api]
      port = 8080

      [payload-validator]
      engine-api-endpoint = { endpoint = "http://OVEERRIDE:8550" }
      eth-api-endpoint = { endpoint = "http://localhost:8545" }

      [syncing]
      peer-chain-height-polling-interval = "10 seconds"
      sync-target-selection = "Highest"
      el-sync-status-refresh-interval = "5 seconds"
      """.trimIndent()

    private val expectedMaruGenesisJson =
      """
      {
        "chainId": 59144,
        "config": {}
      }
      """.trimIndent()

    private lateinit var tempMaruConfigFile: File
    private lateinit var tempMaruConfigOverridesFile: File
    private lateinit var tempMaruGenesisFile: File

    @BeforeAll
    @JvmStatic
    fun beforeAll() {
      tempMaruConfigFile =
        File.createTempFile("MaruAppCliTest", ".toml").also {
          it.writeText(maruConfigDtoToml)
        }
      tempMaruConfigOverridesFile =
        File.createTempFile("MaruAppCliTest", ".toml").also {
          it.writeText(maruConfigOverridesDtoToml)
        }
      tempMaruGenesisFile =
        File.createTempFile("MaruAppCliTest", ".json").also {
          it.writeText(expectedMaruGenesisJson)
        }
    }

    @AfterAll
    @JvmStatic
    fun afterAll() {
      listOf(tempMaruConfigFile, tempMaruConfigOverridesFile, tempMaruGenesisFile).forEach {
        if (it.exists()) {
          it.delete()
        }
      }
    }
  }

  private val mockMaruAppFactory = Mockito.mock(MaruAppFactory::class.java)
  private val cmd = CommandLine(MaruAppCli(mockMaruAppFactory))

  @BeforeEach
  fun setUp() {
    whenever(
      mockMaruAppFactory.create(
        any(),
        any(),
        anyOrNull(),
        anyOrNull(),
        anyOrNull(),
        anyOrNull(),
        anyOrNull(),
        anyOrNull(),
      ),
    ).thenReturn(NoOpMaruApp)

    cmd.registerConverter(
      Network::class.java,
      KebabToEnumConverter(Network::class.java),
    )
  }

  @Test
  fun `should parse commandline args with 'network' as linea-mainnet`() {
    val args =
      listOf(
        "--config=${tempMaruConfigFile.absolutePath}",
        "--network=linea-mainnet",
      )
    val exitCode = cmd.execute(*args.toTypedArray())
    assertThat(exitCode).isEqualTo(0)

    val cli = cmd.getCommand<MaruAppCli>()
    assertThat(cli.genesisOptions!!.network!!.networkNameInKebab).isEqualTo("linea-mainnet")
    assertThat(cli.genesisOptions!!.genesisFile!!).isEqualTo(buildInGenesisFileResourcePath("linea-mainnet"))
    assertThat(cli.configFiles!!.first().path).isEqualTo(tempMaruConfigFile.absolutePath)
  }

  @Test
  fun `should parse commandline args with network as linea-seoplia with case-insensitive`() {
    val args =
      listOf(
        "--config=${tempMaruConfigFile.absolutePath}",
        "--network=LINEA-SEPOLIA",
      )
    val exitCode = cmd.execute(*args.toTypedArray())
    assertThat(exitCode).isEqualTo(0)

    val cli = cmd.getCommand<MaruAppCli>()
    assertThat(cli.genesisOptions!!.network!!.networkNameInKebab).isEqualTo("linea-sepolia")
    assertThat(cli.genesisOptions!!.genesisFile!!).isEqualTo(buildInGenesisFileResourcePath("linea-sepolia"))
    assertThat(cli.configFiles!!.first().path).isEqualTo(tempMaruConfigFile.absolutePath)
  }

  @Test
  fun `should parse commandline args with 'maru-genesis-file' specified`() {
    val args =
      listOf(
        "--config=${tempMaruConfigFile.absolutePath}",
        "--maru-genesis-file=${tempMaruGenesisFile.absolutePath}",
      )
    val exitCode = cmd.execute(*args.toTypedArray())
    assertThat(exitCode).isEqualTo(0)

    val cli = cmd.getCommand<MaruAppCli>()
    assertThat(cli.genesisOptions!!.network).isNull()
    assertThat(cli.genesisOptions!!.genesisFile!!).isEqualTo(tempMaruGenesisFile.absolutePath)
    assertThat(cli.configFiles!!.first().path).isEqualTo(tempMaruConfigFile.absolutePath)
  }

  @Test
  fun `should parse commandline args with 'genesis-file' specified`() {
    val args =
      listOf(
        "--config=${tempMaruConfigFile.absolutePath}",
        "--genesis-file=${tempMaruGenesisFile.absolutePath}",
      )
    val exitCode = cmd.execute(*args.toTypedArray())
    assertThat(exitCode).isEqualTo(0)

    val cli = cmd.getCommand<MaruAppCli>()
    assertThat(cli.genesisOptions!!.network).isNull()
    assertThat(cli.genesisOptions!!.genesisFile!!).isEqualTo(tempMaruGenesisFile.absolutePath)
    assertThat(cli.configFiles!!.first().path).isEqualTo(tempMaruConfigFile.absolutePath)
  }

  @Test
  fun `should parse commandline args with comma-separated configs with latter overrides former`() {
    val args =
      listOf(
        "--config=${tempMaruConfigFile.absolutePath},${tempMaruConfigOverridesFile.absolutePath}",
        "--genesis-file=${tempMaruGenesisFile.absolutePath}",
      )
    val exitCode = cmd.execute(*args.toTypedArray())
    assertThat(exitCode).isEqualTo(0)

    val expectedMaruConfig = parseConfig<MaruConfigDtoToml>(expectedMaruConfigDtoToml).domainFriendly()
    val expectedMaruGenesis = parseBeaconChainConfig(expectedMaruGenesisJson).domainFriendly()

    verify(mockMaruAppFactory).create(
      eq(expectedMaruConfig),
      eq(expectedMaruGenesis),
      anyOrNull(),
      anyOrNull(),
      anyOrNull(),
      anyOrNull(),
      anyOrNull(),
      anyOrNull(),
    )

    val cli = cmd.getCommand<MaruAppCli>()
    assertThat(cli.genesisOptions!!.network).isNull()
    assertThat(cli.genesisOptions!!.genesisFile!!).isEqualTo(tempMaruGenesisFile.absolutePath)
    assertThat(cli.configFiles!![0].path).isEqualTo(tempMaruConfigFile.absolutePath)
    assertThat(cli.configFiles[1].path).isEqualTo(tempMaruConfigOverridesFile.absolutePath)
  }

  @Test
  fun `should parse commandline args with multiple configs with latter overrides former`() {
    val args =
      listOf(
        "--config=${tempMaruConfigFile.absolutePath}",
        "--config=${tempMaruConfigOverridesFile.absolutePath}",
        "--genesis-file=${tempMaruGenesisFile.absolutePath}",
      )
    val exitCode = cmd.execute(*args.toTypedArray())
    assertThat(exitCode).isEqualTo(0)

    val expectedMaruConfig = parseConfig<MaruConfigDtoToml>(expectedMaruConfigDtoToml).domainFriendly()
    val expectedMaruGenesis = parseBeaconChainConfig(expectedMaruGenesisJson).domainFriendly()

    verify(mockMaruAppFactory).create(
      eq(expectedMaruConfig),
      eq(expectedMaruGenesis),
      anyOrNull(),
      anyOrNull(),
      anyOrNull(),
      anyOrNull(),
      anyOrNull(),
      anyOrNull(),
    )

    val cli = cmd.getCommand<MaruAppCli>()
    assertThat(cli.genesisOptions!!.network).isNull()
    assertThat(cli.genesisOptions!!.genesisFile!!).isEqualTo(tempMaruGenesisFile.absolutePath)
    assertThat(cli.configFiles!![0].path).isEqualTo(tempMaruConfigFile.absolutePath)
    assertThat(cli.configFiles[1].path).isEqualTo(tempMaruConfigOverridesFile.absolutePath)
  }

  @Test
  fun `should parse commandline args and default to linea-mainnet with only 'config' specified`() {
    val args =
      listOf(
        "--config=${tempMaruConfigFile.absolutePath}",
      )
    val exitCode = cmd.execute(*args.toTypedArray())
    assertThat(exitCode).isEqualTo(0)

    val cli = cmd.getCommand<MaruAppCli>()
    assertThat(cli.genesisOptions!!.network!!.networkNameInKebab).isEqualTo("linea-mainnet")
    assertThat(cli.genesisOptions!!.genesisFile!!).isEqualTo(buildInGenesisFileResourcePath("linea-mainnet"))
    assertThat(cli.configFiles!!.first().path).isEqualTo(tempMaruConfigFile.absolutePath)
  }

  @Test
  fun `should fail to parse commandline args with both 'genesis-file' and 'network' specified`() {
    val args =
      listOf(
        "--config=./maru.config.toml",
        "--genesis-file=./maru.genesis.json",
        "--network=linea-mainnet",
      )
    val exitCode = cmd.execute(*args.toTypedArray())
    assertThat(exitCode).isEqualTo(2)
  }

  @Test
  fun `should fail to parse commandline args without 'config' specified`() {
    val args =
      listOf(
        "--network=linea-mainnet",
      )
    val exitCode = cmd.execute(*args.toTypedArray())
    assertThat(exitCode).isEqualTo(2)
  }

  @Test
  fun `should fail to parse commandline args with 'network' as both linea-mainnet and linea-sepolia`() {
    val args =
      listOf(
        "--config=./maru.config.toml",
        "--network=linea-mainnet",
        "--network=linea-sepolia",
      )
    val exitCode = cmd.execute(*args.toTypedArray())
    assertThat(exitCode).isEqualTo(2)
  }

  @Test
  fun `should fail to parse commandline args with invalid 'network'`() {
    val args =
      listOf(
        "--config=./maru.config.toml",
        "--network=linea-testnet",
      )
    val exitCode = cmd.execute(*args.toTypedArray())
    assertThat(exitCode).isEqualTo(2)
  }

  @Test
  fun `should fail to parse commandline args with 'genesis-file' and 'maru-genesis-file' both specified`() {
    val args =
      listOf(
        "--config=./maru.config.toml",
        "--genesis-file=./maru.genesis.json",
        "--maru-genesis-file=./maru.genesis.json",
      )
    val exitCode = cmd.execute(*args.toTypedArray())
    assertThat(exitCode).isEqualTo(2)
  }
}
