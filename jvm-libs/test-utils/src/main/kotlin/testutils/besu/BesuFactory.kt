/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package testutils.besu

import java.util.Collections.singletonList
import java.util.Optional
import kotlin.jvm.optionals.getOrDefault
import kotlin.random.Random
import linea.kotlin.encodeHex
import org.hyperledger.besu.consensus.qbft.QbftExtraDataCodec
import org.hyperledger.besu.crypto.KeyPairUtil
import org.hyperledger.besu.ethereum.api.jsonrpc.JsonRpcConfiguration
import org.hyperledger.besu.ethereum.core.AddressHelpers
import org.hyperledger.besu.ethereum.core.Util
import org.hyperledger.besu.ethereum.core.ImmutableMiningConfiguration
import org.hyperledger.besu.ethereum.core.ImmutableMiningConfiguration.MutableInitValues
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier
import org.hyperledger.besu.plugin.services.storage.KeyValueStorageFactory
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBKeyValueStorageFactory
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBMetricsFactory
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBCLIOptions
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode
import org.hyperledger.besu.tests.acceptance.dsl.node.configuration.BesuNodeConfigurationBuilder
import org.hyperledger.besu.tests.acceptance.dsl.node.configuration.BesuNodeFactory
import org.hyperledger.besu.tests.acceptance.dsl.node.configuration.genesis.GenesisConfigurationFactory

object BesuFactory {
  private const val PRAGUE_GENESIS = "/el_prague.json"
  private const val QBFT_LONDON_GENESIS = "/qbft/qbft-london.json"

  private val elPragueCliqueConsensusJson: Regex =
    Regex(
      """"clique"\s*:\s*\{\s*"createemptyblocks"\s*:\s*false\s*,\s*"blockperiodseconds"\s*:\s*1\s*,\s*"epochlength"\s*:\s*1\s*\}""",
    )

  const val MIN_BLOCK_TIME = 1L
  const val BLOCK_REBUILD_TIME = 15L

  private val qbftLondonGenesisTemplate: String by lazy {
    BesuFactory::class.java.getResourceAsStream(QBFT_LONDON_GENESIS)?.use { it.reader().readText() }
      ?: error("Missing resource $QBFT_LONDON_GENESIS (from Besu acceptance tests)")
  }

  fun buildTestBesu(
    genesisFile: String = GenesisConfigurationFactory.readGenesisFile(PRAGUE_GENESIS),
    validator: Boolean = true,
    engineRpcPort: Optional<Int> = Optional.empty(),
    jsonRpcPort: Optional<Int> = Optional.empty(),
    nodeName: String = "miner-${Random.nextBytes(4).encodeHex(false)}",
    syncMinimumPeerCount: Int = 1,
  ): BesuNode =
    BesuNodeFactory().createNode(nodeName) { builder: BesuNodeConfigurationBuilder ->
      val persistentStorageFactory: KeyValueStorageFactory =
        RocksDBKeyValueStorageFactory(
          RocksDBCLIOptions.create()::toDomainObject,
          KeyValueSegmentIdentifier.entries,
          RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS,
        )

      val engineRpcConfig = JsonRpcConfiguration.createEngineDefault()
      engineRpcConfig.setEnabled(true)
      engineRpcConfig.setPort(engineRpcPort.getOrDefault(0))
      engineRpcConfig.host = "127.0.0.1"
      engineRpcConfig.setHostsAllowlist(singletonList("*"))
      engineRpcConfig.setAuthenticationEnabled(false)

      val jsonRpcConfig = JsonRpcConfiguration.createDefault()
      jsonRpcConfig.setEnabled(true)
      jsonRpcConfig.setPort(jsonRpcPort.getOrDefault(0))
      jsonRpcConfig.setHostsAllowlist(singletonList("*"))

      builder
        .storageImplementation(persistentStorageFactory)
        .genesisConfigProvider {
          Optional.of(
            genesisFile,
          )
        }.devMode(false)
        .discoveryEnabled(true)
        .engineJsonRpcConfiguration(engineRpcConfig)
        .jsonRpcConfiguration(jsonRpcConfig)
        .synchronizerConfiguration(
          SynchronizerConfiguration
            .builder()
            .syncMinimumPeerCount(syncMinimumPeerCount)
            .build(),
        )

      // Cap the inter-rebuild sleep to BLOCK_REBUILD_TIME (15 ms) for ALL nodes.
      // Without this, Besu's default of 500 ms makes engine_getPayload's empty-block
      // path (awaitCurrentBuildCompletion) block for up to 500 ms instead of 15 ms.
      val miningConfigBuilder =
        ImmutableMiningConfiguration
          .builder()
          .unstable(
            ImmutableMiningConfiguration.Unstable
              .builder()
              .posBlockFinalizationTimeoutMs(10)
              .posBlockCreationRepetitionMinDuration(BLOCK_REBUILD_TIME)
              .build(),
          )

      if (validator) {
        val defaultSigner = KeyPairUtil.loadKeyPairFromResource("default-signer-key")
        miningConfigBuilder.mutableInitValues(
          MutableInitValues
            .builder()
            .coinbase(AddressHelpers.ofValue(1))
            .isMiningEnabled(true)
            .build(),
        )
        builder
          .miningConfiguration(miningConfigBuilder.build())
          .keyPair(defaultSigner)
      } else {
        builder.miningConfiguration(miningConfigBuilder.build())
      }
    }

  /**
   * In-process Besu nodes using QBFT consensus with a genesis built from
   * [GenesisConfigurationFactory.createQbftLondonGenesisConfig] once all cluster nodes are known.
   * Besu 26.3+ no longer seals Clique blocks; QBFT is used for multi-node EL tests instead.
   */
  fun buildTestBesuQbftCluster(
    nodeName: String,
    miningEnabled: Boolean = true,
    engineRpcPort: Optional<Int> = Optional.empty(),
    jsonRpcPort: Optional<Int> = Optional.empty(),
  ): BesuNode =
    BesuNodeFactory().createNode(nodeName) { builder: BesuNodeConfigurationBuilder ->
      val persistentStorageFactory: KeyValueStorageFactory =
        RocksDBKeyValueStorageFactory(
          RocksDBCLIOptions.create()::toDomainObject,
          KeyValueSegmentIdentifier.entries,
          RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS,
        )

      val engineRpcConfig = JsonRpcConfiguration.createEngineDefault()
      engineRpcConfig.setEnabled(true)
      engineRpcConfig.setPort(engineRpcPort.getOrDefault(0))
      engineRpcConfig.host = "127.0.0.1"
      engineRpcConfig.setHostsAllowlist(singletonList("*"))
      engineRpcConfig.setAuthenticationEnabled(false)

      val jsonRpcConfig = JsonRpcConfiguration.createDefault()
      jsonRpcConfig.setEnabled(true)
      jsonRpcConfig.setPort(jsonRpcPort.getOrDefault(0))
      jsonRpcConfig.setHostsAllowlist(singletonList("*"))

      builder
        .storageImplementation(persistentStorageFactory)
        .genesisConfigProvider { nodes ->
          val sorted = nodes.sortedBy { it.name }
          val addresses = sorted.map { it.address }
          val extraData = QbftExtraDataCodec.createGenesisExtraDataString(addresses)
          Optional.of(qbftLondonGenesisTemplate.replace("%extraData%", extraData))
        }.devMode(false)
        .discoveryEnabled(true)
        .engineJsonRpcConfiguration(engineRpcConfig)
        .jsonRpcConfiguration(jsonRpcConfig)
        .synchronizerConfiguration(
          SynchronizerConfiguration
            .builder()
            .syncMinimumPeerCount(1)
            .build(),
        )

      val miningConfigBuilder =
        ImmutableMiningConfiguration
          .builder()
          .unstable(
            ImmutableMiningConfiguration.Unstable
              .builder()
              .posBlockFinalizationTimeoutMs(10)
              .posBlockCreationRepetitionMinDuration(BLOCK_REBUILD_TIME)
              .build(),
          )

      if (miningEnabled) {
        miningConfigBuilder.mutableInitValues(
          MutableInitValues
            .builder()
            .coinbase(AddressHelpers.ofValue(1))
            .isMiningEnabled(true)
            .build(),
        )
        builder.miningConfiguration(miningConfigBuilder.build())
      } else {
        builder.miningConfiguration(miningConfigBuilder.build())
      }
    }

  /**
   * Merge-ready Prague genesis with **QBFT** pre-merge consensus (same fork timestamps / TTD wiring as
   * [buildSwitchableBesu]); genesis `extraData` lists the default test signer as the sole QBFT validator,
   * matching [buildTestBesu] when `validator` is true.
   */
  fun buildSwitchableBesuQbft(
    pragueTimestamp: ULong = 0UL,
    cancunTimestamp: ULong = pragueTimestamp,
    shanghaiTimestamp: ULong = cancunTimestamp,
    ttd: ULong = 0UL,
    validator: Boolean,
  ): BesuNode {
    val genesisContent =
      BesuFactory::class.java
        .getResourceAsStream(PRAGUE_GENESIS)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: throw IllegalStateException("Could not read genesis file: $PRAGUE_GENESIS")

    val qbftConsensusBlock =
      """
          "qbft": {
            "blockperiodseconds": 1,
            "epochlength": 30000,
            "requesttimeoutseconds": 5,
            "blockreward": "5000000000000000000"
          }
      """.trimIndent()

    val withQbft =
      genesisContent
        .replace(elPragueCliqueConsensusJson, qbftConsensusBlock)
        .replace(Regex(""""extraData"\s*:\s*"0x[0-9a-fA-F]+""""), """"extraData": "%extraData%"""")
    require(withQbft.contains("\"qbft\"")) { "Clique→QBFT genesis rewrite failed: missing qbft config" }
    require(!withQbft.contains("\"clique\"")) { "Clique→QBFT genesis rewrite failed: clique still present" }

    val genesisWithForks =
      withQbft
        .replace("\"shanghaiTime\": 0", "\"shanghaiTime\": $shanghaiTimestamp")
        .replace("\"cancunTime\": 0", "\"cancunTime\": $cancunTimestamp")
        .replace("\"pragueTime\": 0", "\"pragueTime\": $pragueTimestamp")
        .replace("\"terminalTotalDifficulty\": 0", "\"terminalTotalDifficulty\": $ttd")

    val defaultSigner = KeyPairUtil.loadKeyPairFromResource("default-signer-key")
    val validatorAddress = Util.publicKeyToAddress(defaultSigner.publicKey)
    val extraDataHex = QbftExtraDataCodec.createGenesisExtraDataString(listOf(validatorAddress))
    val genesisFile = genesisWithForks.replace("%extraData%", extraDataHex)

    return buildTestBesu(
      genesisFile = genesisFile,
      validator = validator,
      syncMinimumPeerCount = 0,
    )
  }

  fun buildSwitchableBesu(
    pragueTimestamp: ULong = 0UL,
    cancunTimestamp: ULong = pragueTimestamp,
    shanghaiTimestamp: ULong = cancunTimestamp,
    ttd: ULong = 0UL,
    validator: Boolean,
  ): BesuNode {
    val genesisContent =
      BesuFactory::class.java
        .getResourceAsStream(PRAGUE_GENESIS)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: throw IllegalStateException("Could not read genesis file: $PRAGUE_GENESIS")

    val genesisFile =
      genesisContent
        .replace("\"shanghaiTime\": 0", "\"shanghaiTime\": $shanghaiTimestamp")
        .replace("\"cancunTime\": 0", "\"cancunTime\": $cancunTimestamp")
        .replace("\"pragueTime\": 0", "\"pragueTime\": $pragueTimestamp")
        .replace("\"terminalTotalDifficulty\": 0", "\"terminalTotalDifficulty\": $ttd")
    return buildTestBesu(genesisFile = genesisFile, validator = validator)
  }
}
