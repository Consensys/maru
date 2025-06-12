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
import io.micrometer.core.instrument.MeterRegistry
import io.vertx.micrometer.backends.BackendRegistries
import java.time.Clock
import java.util.Optional
import maru.config.FollowersConfig
import maru.config.MaruConfig
import maru.config.consensus.ElFork
import maru.consensus.BlockMetadata
import maru.consensus.ForksSchedule
import maru.consensus.LatestBlockMetadataCache
import maru.consensus.NewBlockHandler
import maru.consensus.NewBlockHandlerMultiplexer
import maru.consensus.NextBlockTimestampProviderImpl
import maru.consensus.OmniProtocolFactory
import maru.consensus.ProtocolStarter
import maru.consensus.ProtocolStarterBlockHandler
import maru.consensus.SealedBeaconBlockHandlerAdapter
import maru.consensus.Web3jMetadataProvider
import maru.consensus.blockimport.FollowerBeaconBlockImporter
import maru.consensus.blockimport.NewSealedBeaconBlockHandlerMultiplexer
import maru.consensus.delegated.ElDelegatedConsensusFactory
import maru.consensus.state.FinalizationProvider
import maru.consensus.state.InstantFinalizationProvider
import maru.core.Protocol
import maru.crypto.Crypto
import maru.database.kv.KvDatabaseFactory
import maru.p2p.P2PNetwork
import maru.p2p.SealedBeaconBlockBroadcaster
import maru.p2p.ValidationResult
import maru.serialization.rlp.RLPSerializers
import net.consensys.linea.async.get
import net.consensys.linea.metrics.Tag
import net.consensys.linea.metrics.micrometer.MicrometerMetricsFacade
import net.consensys.linea.vertx.ObservabilityServer
import net.consensys.linea.vertx.VertxFactory
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.hyperledger.besu.plugin.services.metrics.MetricCategory
import tech.pegasys.teku.infrastructure.async.SafeFuture

class MaruApp(
  val config: MaruConfig,
  beaconGenesisConfig: ForksSchedule,
  clock: Clock = Clock.systemUTC(),
  // This will only be used if config.p2pConfig is undefined
  private var p2pNetwork: P2PNetwork,
  private val privateKeyProvider: () -> ByteArray,
  private val finalizationProvider: FinalizationProvider = InstantFinalizationProvider,
) : AutoCloseable {
  private val log: Logger = LogManager.getLogger(this::javaClass)

  private val vertx =
    VertxFactory.createVertx(
      jvmMetricsEnabled = config.observabilityOptions.jvmMetricsEnabled,
      prometheusMetricsEnabled = config.observabilityOptions.prometheusMetricsEnabled,
    )

  private var privateKeyBytes: ByteArray =
    GeneratingFilePrivateKeySource(
      config.persistence.privateKeyPath.toString(),
    ).privateKeyBytes.toArray()

  private val nodeId = PeerId.fromPubKey(unmarshalPrivateKey(privateKeyBytes).publicKey())
  private val meterRegistry: MeterRegistry = BackendRegistries.getDefaultNow()
  private val metricsFacade =
    MicrometerMetricsFacade(
      meterRegistry,
      "maru",
      allMetricsCommonTags = listOf(Tag("nodeid", nodeId.toBase58())),
    )

  init {
    if (config.qbftOptions == null) {
      log.info("Qbft options are not defined. Maru is running in follower-only node")
    }
    if (config.p2pConfig == null) {
      log.info("P2PManager is not defined.")
    }
    log.info(config.toString())
  }

  fun p2pPort(): UInt = p2pNetwork.port

  private val ethereumJsonRpcClient =
    Helpers.createWeb3jClient(
      config.validatorElNode.ethApiEndpoint,
    )

  private val asyncMetadataProvider = Web3jMetadataProvider(ethereumJsonRpcClient.eth1Web3j)
  private val lastBlockMetadataCache: LatestBlockMetadataCache =
    LatestBlockMetadataCache(asyncMetadataProvider.getLatestBlockMetadata())
  private val metadataProviderCacheUpdater =
    NewBlockHandler<Unit> { beaconBlock ->
      val blockMetadata = BlockMetadata.fromBeaconBlock(beaconBlock)
      lastBlockMetadataCache.updateLatestBlockMetadata(blockMetadata)
      SafeFuture.completedFuture(Unit)
    }
  private val nextTargetBlockTimestampProvider =
    NextBlockTimestampProviderImpl(
      clock = clock,
      forksSchedule = beaconGenesisConfig,
    )
  private val metricsSystem = NoOpMetricsSystem()

  private val metricsSystem = NoOpMetricsSystem()
  private val beaconChain =
    KvDatabaseFactory
      .createRocksDbDatabase(
        databasePath = config.persistence.dataPath,
        metricsSystem = metricsSystem,
        metricCategory =
          object : MetricCategory {
            override fun getName(): String = "STORAGE"

            override fun getApplicationPrefix(): Optional<String> = Optional.empty()
          },
      )
  private val protocolStarter = createProtocolStarter(config, beaconGenesisConfig, clock)

  @Suppress("UNCHECKED_CAST")
  private fun createFollowerHandlers(followers: FollowersConfig): Map<String, NewBlockHandler<Unit>> =
    followers.followers
      .mapValues {
        val engineApiClient = Helpers.buildExecutionEngineClient(it.value, ElFork.Prague)
        FollowerBeaconBlockImporter.create(engineApiClient, finalizationProvider) as NewBlockHandler<Unit>
      }

  fun start() {
    try {
      vertx
        .deployVerticle(
          ObservabilityServer(
            ObservabilityServer.Config(applicationName = "maru", port = config.observabilityOptions.port.toInt()),
          ),
        ).get()
    } catch (th: Throwable) {
      log.error("Error while trying to start the observability server", th)
      throw th
    }
    try {
      p2pNetwork.start().get()
    } catch (th: Throwable) {
      log.error("Error while trying to start the P2P network", th)
      throw th
    }
    protocolStarter.start()
    log.info("Maru is up")
  }

  fun stop() {
    try {
      vertx.deploymentIDs().forEach {
        vertx.undeploy(it).get()
      }
    } catch (th: Throwable) {
      log.warn("Error while trying to stop the vertx verticles", th)
    }
    try {
      p2pNetwork.stop().get()
    } catch (th: Throwable) {
      log.warn("Error while trying to stop the P2P network", th)
    }
    protocolStarter.stop()
    log.info("Maru is down")
  }

  override fun close() {
    beaconChain.close()
  }

  private fun createProtocolStarter(
    config: MaruConfig,
    beaconGenesisConfig: ForksSchedule,
    clock: Clock,
  ): Protocol {
    val metadataCacheUpdaterHandlerEntry = "latest block metadata updater" to metadataProviderCacheUpdater

    val followerHandlersMap: Map<String, NewBlockHandler<Unit>> =
      createFollowerHandlers(config.followers)
    val followerBlockHandlers = followerHandlersMap + metadataCacheUpdaterHandlerEntry
    val blockImportHandlers =
      NewBlockHandlerMultiplexer(followerBlockHandlers)
    val adaptedBeaconBlockImporter = SealedBeaconBlockHandlerAdapter(blockImportHandlers)

    val beaconChainInitialization =
      BeaconChainInitialization(
        executionLayerClient = ethereumJsonRpcClient.eth1Web3j,
        beaconChain = beaconChain,
      )

    val qbftFactory =
      if (config.qbftOptions != null) {
        val sealedBlockHandlers =
          mutableMapOf(
            "beacon block handlers" to adaptedBeaconBlockImporter,
            "p2p broadcast sealed beacon block handler" to
              SealedBeaconBlockBroadcaster(p2pNetwork),
          )
        val sealedBlockHandlerMultiplexer =
          NewSealedBeaconBlockHandlerMultiplexer<Unit>(
            handlersMap = sealedBlockHandlers,
          )
        QbftProtocolFactoryWithBeaconChainInitialization(
          qbftOptions = config.qbftOptions!!,
          privateKeyBytes = Crypto.privateKeyBytesWithoutPrefix(privateKeyProvider()),
          validatorElNodeConfig = config.validatorElNode,
          metricsSystem = metricsSystem,
          finalizationStateProvider = finalizationProvider,
          nextTargetBlockTimestampProvider = nextTargetBlockTimestampProvider,
          newBlockHandler = sealedBlockHandlerMultiplexer,
          beaconChain = beaconChain,
          clock = clock,
          p2pNetwork = p2pNetwork,
          beaconChainInitialization = beaconChainInitialization,
          metricsFacade = metricsFacade,
        )
      } else {
        QbftFollowerFactory(
          p2PNetwork = p2pNetwork,
          beaconChain = beaconChain,
          newBlockHandler = blockImportHandlers,
          validatorElNodeConfig = config.validatorElNode,
          beaconChainInitialization = beaconChainInitialization,
          metricsFacade = metricsFacade,
        )
      }
    val delegatedConsensusNewBlockHandler =
      NewBlockHandlerMultiplexer(
        mapOf(metadataCacheUpdaterHandlerEntry),
      )

    val protocolStarter =
      ProtocolStarter(
        forksSchedule = beaconGenesisConfig,
        protocolFactory =
          OmniProtocolFactory(
            elDelegatedConsensusFactory =
              ElDelegatedConsensusFactory(
                ethereumJsonRpcClient = ethereumJsonRpcClient.eth1Web3j,
                newBlockHandler = delegatedConsensusNewBlockHandler,
              ),
            qbftConsensusFactory = qbftFactory,
          ),
        metadataProvider = lastBlockMetadataCache,
        nextBlockTimestampProvider = nextTargetBlockTimestampProvider,
      )

    val protocolStarterBlockHandlerEntry = "protocol starter" to ProtocolStarterBlockHandler(protocolStarter)
    delegatedConsensusNewBlockHandler.addHandler(
      protocolStarterBlockHandlerEntry.first,
    ) {
      protocolStarterBlockHandlerEntry.second.handleNewBlock(it)
    }
    blockImportHandlers.addHandler(
      protocolStarterBlockHandlerEntry.first,
    ) {
      protocolStarterBlockHandlerEntry.second
        .handleNewBlock(it)
        .thenApply { ValidationResult.Companion.Valid }
    }

    return protocolStarter
  }
}
