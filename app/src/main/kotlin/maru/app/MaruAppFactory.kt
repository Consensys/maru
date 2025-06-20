/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.unmarshalPrivateKey
import io.vertx.micrometer.backends.BackendRegistries
import java.nio.file.Path
import java.time.Clock
import java.util.Optional
import maru.config.MaruConfig
import maru.config.P2P
import maru.consensus.ForkIdHashProvider
import maru.consensus.ForkIdHasher
import maru.consensus.ForksSchedule
import maru.crypto.Hashing
import maru.database.kv.KvDatabaseFactory
import maru.p2p.NoOpP2PNetwork
import maru.p2p.P2PNetwork
import maru.p2p.P2PNetworkImpl
import maru.p2p.RpcMethodFactory
import maru.serialization.ForkIdSerializers
import maru.serialization.rlp.RLPSerializers
import net.consensys.linea.metrics.MetricsFacade
import net.consensys.linea.metrics.Tag
import net.consensys.linea.metrics.micrometer.MicrometerMetricsFacade
import net.consensys.linea.vertx.VertxFactory
import org.apache.logging.log4j.LogManager
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.hyperledger.besu.plugin.services.metrics.MetricCategory
import tech.pegasys.teku.networking.p2p.network.config.GeneratingFilePrivateKeySource

class MaruAppFactory {
  private val log = LogManager.getLogger(MaruAppFactory::class.java)

  fun create(
    config: MaruConfig,
    beaconGenesisConfig: ForksSchedule,
    clock: Clock = Clock.systemUTC(),
    overridingP2PNetwork: P2PNetwork? = null,
  ): MaruApp {
    val privateKey = getOrGeneratePrivateKey(config.persistence.privateKeyPath)
    val vertx =
      VertxFactory.createVertx(
        jvmMetricsEnabled = config.observabilityOptions.jvmMetricsEnabled,
        prometheusMetricsEnabled = config.observabilityOptions.prometheusMetricsEnabled,
      )

    val nodeId = PeerId.fromPubKey(unmarshalPrivateKey(privateKey).publicKey())
    val metricsFacade =
      MicrometerMetricsFacade(
        BackendRegistries.getDefaultNow(),
        "maru",
        allMetricsCommonTags = listOf(Tag("nodeid", nodeId.toBase58())),
      )
    val besuMetricsSystem = NoOpMetricsSystem()

    val beaconChain =
      KvDatabaseFactory
        .createRocksDbDatabase(
          databasePath = config.persistence.dataPath,
          metricsSystem = besuMetricsSystem,
          metricCategory =
            object : MetricCategory {
              override fun getName(): String = "STORAGE"

              override fun getApplicationPrefix(): Optional<String> = Optional.empty()
            },
        )

    val forkIdHasher =
      ForkIdHasher(
        ForkIdSerializers
          .ForkIdSerializer,
        Hashing::shortShaHash,
      )
    val forkIdHashProvider =
      ForkIdHashProvider(
        chainId = beaconGenesisConfig.chainId,
        beaconChain = beaconChain,
        forksSchedule = beaconGenesisConfig,
        forkIdHasher = forkIdHasher,
      )
    val rpcMaruAppFactory =
      RpcMethodFactory(
        beaconChain = beaconChain,
        forkIdHashProvider = forkIdHashProvider,
        chainId = beaconGenesisConfig.chainId,
      )
    val p2pNetwork =
      overridingP2PNetwork ?: setupP2PNetwork(
        p2pConfig = config.p2pConfig,
        privateKey = privateKey,
        chainId = beaconGenesisConfig.chainId,
        metricsFacade = metricsFacade,
        rpcMethodFactory = rpcMaruAppFactory,
      )

    val maru =
      MaruApp(
        config = config,
        beaconGenesisConfig = beaconGenesisConfig,
        clock = clock,
        p2pNetwork = p2pNetwork,
        privateKeyProvider = { privateKey },
        metricsFacade = metricsFacade,
        vertx = vertx,
        beaconChain = beaconChain,
        metricsSystem = besuMetricsSystem,
      )

    return maru
  }

  companion object {
    private val log = LogManager.getLogger(MaruApp::class.java)

    fun setupP2PNetwork(
      p2pConfig: P2P?,
      privateKey: ByteArray,
      chainId: UInt,
      metricsFacade: MetricsFacade,
      rpcMethodFactory: RpcMethodFactory,
    ): P2PNetwork =
      p2pConfig?.let {
        P2PNetworkImpl(
          privateKeyBytes = privateKey,
          p2pConfig = p2pConfig,
          chainId = chainId,
          serDe = RLPSerializers.SealedBeaconBlockSerializer,
          metricsFacade = metricsFacade,
          rpcMethodFactory = rpcMethodFactory,
        )
      } ?: run {
        log.info("No P2P configuration provided, using NoOpP2PNetwork")
        NoOpP2PNetwork
      }

    fun getOrGeneratePrivateKey(privateKeyPath: Path): ByteArray {
      if (!privateKeyPath
          .toFile()
          .exists()
      ) {
        log.info(
          "Private key file {} does not exist. A new private key will be generated and stored in that location.",
          privateKeyPath.toString(),
        )
      } else {
        log.info("Maru is using private key defined in file={}", privateKeyPath.toString())
      }

      return GeneratingFilePrivateKeySource(privateKeyPath.toString()).privateKeyBytes.toArray()
    }
  }
}
