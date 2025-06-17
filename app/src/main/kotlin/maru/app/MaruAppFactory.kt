/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.nio.file.Path
import java.time.Clock
import linea.contract.l1.LineaRollupSmartContractClientReadOnly
import linea.contract.l1.Web3JLineaRollupSmartContractClientReadOnly
import linea.web3j.createWeb3jHttpClient
import linea.web3j.ethapi.createEthApiClient
import maru.config.MaruConfig
import maru.config.P2P
import maru.consensus.ForksSchedule
import maru.consensus.state.FinalizationProvider
import maru.consensus.state.InstantFinalizationProvider
import maru.finalization.LineaFinalizationProvider
import maru.p2p.NoOpP2PNetwork
import maru.p2p.P2PNetwork
import maru.p2p.P2PNetworkImpl
import maru.serialization.rlp.RLPSerializers
import org.apache.logging.log4j.LogManager
import tech.pegasys.teku.networking.p2p.network.config.GeneratingFilePrivateKeySource

class MaruAppFactory {
  private val log = LogManager.getLogger(MaruAppFactory::class.java)

  fun create(
    config: MaruConfig,
    beaconGenesisConfig: ForksSchedule,
    clock: Clock = Clock.systemUTC(),
    overridingP2PNetwork: P2PNetwork? = null,
    overridingFinalizationProvider: FinalizationProvider? = null,
    overridingLineaContractClient: LineaRollupSmartContractClientReadOnly? = null,
  ): MaruApp {
    val privateKey = getOrGeneratePrivateKey(config.persistence.privateKeyPath)
    val p2pNetwork =
      overridingP2PNetwork ?: setupP2PNetwork(
        p2pConfig = config.p2pConfig,
        privateKey = privateKey,
        chainId = beaconGenesisConfig.chainId,
      )
    val finalizationProvider =
      overridingFinalizationProvider
        ?: setupFinalizationProvider(config, overridingLineaContractClient)

    val maru =
      MaruApp(
        config = config,
        beaconGenesisConfig = beaconGenesisConfig,
        clock = clock,
        p2pNetwork = p2pNetwork,
        privateKeyProvider = { privateKey },
        finalizationProvider = finalizationProvider,
      )

    return maru
  }

  companion object {
    private val log = LogManager.getLogger(MaruApp::class.java)

    fun setupFinalizationProvider(
      config: MaruConfig,
      overridingLineaContractClient: LineaRollupSmartContractClientReadOnly?,
    ): FinalizationProvider =
      config.linea
        ?.let { lineaConfig ->
          val contractClient =
            overridingLineaContractClient
              ?: Web3JLineaRollupSmartContractClientReadOnly(
                web3j =
                  createWeb3jHttpClient(
                    rpcUrl = lineaConfig.l1EthApi.endpoint.toString(),
                    log = LogManager.getLogger("clients.l1.linea"),
                  ),
                contractAddress = lineaConfig.contractAddress,
                log = LogManager.getLogger("clients.l1.linea"),
              )
          LineaFinalizationProvider(
            lineaContract = contractClient,
            l2EthApi =
              createEthApiClient(
                rpcUrl =
                  config.validatorElNode.ethApiEndpoint.endpoint
                    .toString(),
                log = LogManager.getLogger("clients.l2.eth.el"),
                // FIXME: add support for request retries when we have Vert.x in the app
                requestRetryConfig = null,
                vertx = null, // Vert.x is not used in this context
              ),
            pollingUpdateInterval = lineaConfig.l1PollingInterval,
            l1HighestBlock = lineaConfig.l1HighestBlockTag,
          )
        } ?: InstantFinalizationProvider

    fun setupP2PNetwork(
      p2pConfig: P2P?,
      privateKey: ByteArray,
      chainId: UInt,
    ): P2PNetwork =
      p2pConfig?.let {
        P2PNetworkImpl(
          privateKeyBytes = privateKey,
          p2pConfig = p2pConfig,
          chainId = chainId,
          serDe = RLPSerializers.SealedBeaconBlockSerializer,
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
