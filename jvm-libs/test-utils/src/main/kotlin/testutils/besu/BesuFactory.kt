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
import org.hyperledger.besu.crypto.KeyPairUtil
import org.hyperledger.besu.ethereum.api.jsonrpc.JsonRpcConfiguration
import org.hyperledger.besu.ethereum.core.AddressHelpers
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
  const val MIN_BLOCK_TIME = 1L
  const val BLOCK_REBUILD_TIME = 15L

  fun buildTestBesu(
    genesisFile: String = GenesisConfigurationFactory.readGenesisFile(PRAGUE_GENESIS),
    validator: Boolean = true,
    /**
     * When true, enables fast block building (posBlockCreationRepetitionMinDuration = 15ms) and
     * isMiningEnabled WITHOUT fixing the P2P node key to default-signer-key. Use this for
     * multi-node tests where each node needs a unique enode identity while still benefiting from
     * low-latency engine_getPayload responses.
     *
     * Note: [validator] = true already implies fast block building; this flag is only useful when
     * [validator] = false but fast payloads are still needed (e.g. multi-validator test setups).
     */
    fastBlockBuilding: Boolean = false,
    engineRpcPort: Optional<Int> = Optional.empty(),
    jsonRpcPort: Optional<Int> = Optional.empty(),
    nodeName: String = "miner-${Random.nextBytes(4).encodeHex(false)}",
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
            .syncMinimumPeerCount(1)
            .build(),
        )

      if (validator || fastBlockBuilding) {
        // Cap the inter-rebuild sleep so engine_getPayload's empty-block path
        // (awaitCurrentBuildCompletion) resolves within BLOCK_REBUILD_TIME (15 ms)
        // instead of Besu's default 500 ms.
        val miningConfiguration =
          ImmutableMiningConfiguration
            .builder()
            .mutableInitValues(
              MutableInitValues
                .builder()
                .coinbase(AddressHelpers.ofValue(1))
                .isMiningEnabled(true)
                .build(),
            ).unstable(
              ImmutableMiningConfiguration.Unstable
                .builder()
                .posBlockCreationRepetitionMinDuration(BLOCK_REBUILD_TIME)
                .build(),
            ).build()
        builder.miningConfiguration(miningConfiguration)
      }

      if (validator) {
        // Fix the P2P node key so the node identity is stable and matches the genesis validator
        // set. Only use for single-node tests; multi-node tests must use fastBlockBuilding=true
        // with validator=false to keep unique enode IDs per node.
        val defaultSigner = KeyPairUtil.loadKeyPairFromResource("default-signer-key")
        builder.keyPair(defaultSigner)
      } else {
        builder
      }
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
