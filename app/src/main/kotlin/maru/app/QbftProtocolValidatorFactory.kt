/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.time.Clock
import maru.config.QbftConfig
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import maru.consensus.NextBlockTimestampProvider
import maru.consensus.ProtocolFactory
import maru.consensus.QbftConsensusConfig
import maru.consensus.qbft.QbftEventMultiplexer
import maru.consensus.qbft.QbftValidatorFactory
import maru.consensus.state.FinalizationProvider
import maru.core.Protocol
import maru.database.BeaconChain
import maru.executionlayer.ExecutionLayerFactory.buildExecutionLayerManager
import maru.p2p.P2PNetwork
import maru.p2p.SealedBeaconBlockBroadcaster
import net.consensys.linea.metrics.MetricsFacade
import org.hyperledger.besu.plugin.services.MetricsSystem
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JClient

class QbftProtocolValidatorFactory(
  private val qbftOptions: QbftConfig,
  private val privateKeyBytes: ByteArray,
  private val validatorELNodeEngineApiWeb3JClient: Web3JClient,
  private val followerELNodeEngineApiWeb3JClients: Map<String, Web3JClient>,
  private val metricsSystem: MetricsSystem,
  private val finalizationStateProvider: FinalizationProvider,
  private val beaconChain: BeaconChain,
  private val nextTargetBlockTimestampProvider: NextBlockTimestampProvider,
  private val clock: Clock,
  private val p2pNetwork: P2PNetwork,
  private val metricsFacade: MetricsFacade,
  private val allowEmptyBlocks: Boolean,
  private val forksSchedule: ForksSchedule,
  private val payloadValidationEnabled: Boolean,
  /** Optional: called when BLOCK_TIMER_EXPIRY fires. See [QbftEventMultiplexer.onBlockTimerFired]. */
  private val onBlockTimerFired: ((blockNumber: Long, wallClockMs: Long) -> Unit)? = null,
) : ProtocolFactory {
  override fun create(forkSpec: ForkSpec): Protocol {
    require(forkSpec.configuration is QbftConsensusConfig) {
      "Unexpected fork specification! ${
        forkSpec
          .configuration
      } instead of ${QbftConsensusConfig::class.simpleName}"
    }
    val qbftConsensusConfig = forkSpec.configuration as QbftConsensusConfig

    val payloadValidatorExecutionLayerManager =
      buildExecutionLayerManager(
        web3JEngineApiClient = validatorELNodeEngineApiWeb3JClient,
        elFork = qbftConsensusConfig.elFork,
        metricsFacade = metricsFacade,
      )

    val qbftValidatorFactory =
      QbftValidatorFactory(
        beaconChain = beaconChain,
        privateKeyBytes = privateKeyBytes,
        qbftOptions = qbftOptions,
        metricsSystem = metricsSystem,
        finalizationStateProvider = finalizationStateProvider,
        nextBlockTimestampProvider = nextTargetBlockTimestampProvider,
        blockMinedHandler = SealedBeaconBlockBroadcaster(p2pNetwork),
        executionLayerManager = payloadValidatorExecutionLayerManager,
        clock = clock,
        p2PNetwork = p2pNetwork,
        allowEmptyBlocks = allowEmptyBlocks,
        forksSchedule = forksSchedule,
        payloadValidationEnabled = payloadValidationEnabled,
        onBlockTimerFired = onBlockTimerFired,
      )
    val qbftProtocol = qbftValidatorFactory.create(forkSpec)

    // Subscribe EL driving for the validator's own EL and external follower EL nodes.
    // Fires for all blocks (consensus-committed and CL-synced), keeping Besu in sync
    // with the beacon chain head via newPayload+setHead. For proposer rounds,
    // BlockBuildingBeaconBlockImporter subsequently calls setHeadAndStartBlockBuilding
    // (via thenPeek, after commit()) which overrides the setHead and starts building.
    return qbftProtocol.subscribeElSync(
      beaconChain,
      Helpers.buildElImporterHandlers(
        ownExecutionLayerManager = payloadValidatorExecutionLayerManager,
        ownHandlerName = "validator-el",
        followerELNodeEngineApiWeb3JClients = followerELNodeEngineApiWeb3JClients,
        elFork = qbftConsensusConfig.elFork,
        finalizationStateProvider = finalizationStateProvider,
        metricsFacade = metricsFacade,
      ),
    )
  }
}
