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
import maru.consensus.blockimport.FollowerBeaconBlockImporter
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
  private val onBlockTimerFired: ((blockNumber: Long) -> Unit)? = null,
  /** Optional: called when a QBFT message arrives from P2P, before queue insertion. See [QbftMessageProcessor.onMessageReceived]. */
  private val onMessageReceived: ((msgCode: Int, sequenceNumber: Long) -> Unit)? = null,
  /** Optional: called just before a QBFT message is broadcast via P2P. */
  private val onMessageSent: ((msgCode: Int, sequenceNumber: Long) -> Unit)? = null,
  /** Optional: called when the QBFT event loop starts block import. */
  private val onImportStarted: ((blockNumber: Long) -> Unit)? = null,
  /** Optional: called before every event is dispatched on the event loop. See [QbftEventMultiplexer.onBeforeEvent]. */
  private val onBeforeEvent: ((eventLabel: String) -> Unit)? = null,
  /** Optional: called after every event is processed on the event loop. See [QbftEventMultiplexer.onAfterEvent]. */
  private val onAfterEvent: ((eventLabel: String) -> Unit)? = null,
) : ProtocolFactory {
  override fun create(forkSpec: ForkSpec): Protocol {
    require(forkSpec.configuration is QbftConsensusConfig) {
      "Unexpected fork specification! ${forkSpec.configuration} instead of ${QbftConsensusConfig::class.simpleName}"
    }
    val qbftConsensusConfig = forkSpec.configuration as QbftConsensusConfig

    val payloadValidatorExecutionLayerManager =
      buildExecutionLayerManager(
        web3JEngineApiClient = validatorELNodeEngineApiWeb3JClient,
        elFork = qbftConsensusConfig.elFork,
        metricsFacade = metricsFacade,
      )

    // Build EL import handlers for this validator's own EL and follower EL nodes.
    // Fires for every committed block (both QBFT consensus and CL sync) via the
    // TransactionalSealedBeaconBlockImporter callback inside QbftValidatorFactory.
    val blockImportHandlers =
      Helpers.createBlockImportHandlers(
        elFork = qbftConsensusConfig.elFork,
        metricsFacade = metricsFacade,
        finalizationStateProvider = finalizationStateProvider,
        followerELNodeEngineApiWeb3JClients = followerELNodeEngineApiWeb3JClients,
      )

    // Own EL import handler: newPayload+setHead for every committed block.
    // This ensures the own EL stays in sync even after a besu restart (where it needs
    // newPayload to reimport blocks). The extra newPayload is idempotent.
    val ownElImportHandler =
      FollowerBeaconBlockImporter.create(
        executionLayerManager = payloadValidatorExecutionLayerManager,
        finalizationStateProvider = finalizationStateProvider,
        importerName = "validator-el",
      )
    blockImportHandlers.addHandler("validator-el") { beaconBlock ->
      ownElImportHandler.handleNewBlock(beaconBlock).thenApply { }
    }

    val qbftValidatorFactory =
      QbftValidatorFactory(
        beaconChain = beaconChain,
        privateKeyBytes = privateKeyBytes,
        qbftOptions = qbftOptions,
        metricsSystem = metricsSystem,
        finalizationStateProvider = finalizationStateProvider,
        nextBlockTimestampProvider = nextTargetBlockTimestampProvider,
        blockMinedHandler = SealedBeaconBlockBroadcaster(p2pNetwork),
        newBlockHandler = blockImportHandlers,
        executionLayerManager = payloadValidatorExecutionLayerManager,
        clock = clock,
        p2PNetwork = p2pNetwork,
        allowEmptyBlocks = allowEmptyBlocks,
        forksSchedule = forksSchedule,
        payloadValidationEnabled = payloadValidationEnabled,
        onBlockTimerFired = onBlockTimerFired,
        onMessageReceived = onMessageReceived,
        onMessageSent = onMessageSent,
        onImportStarted = onImportStarted,
        onBeforeEvent = onBeforeEvent,
        onAfterEvent = onAfterEvent,
      )

    return qbftValidatorFactory.create(forkSpec)
  }
}
