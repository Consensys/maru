/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import maru.consensus.ForkSpec
import maru.consensus.ProtocolFactory
import maru.consensus.QbftConsensusConfig
import maru.consensus.StaticValidatorProvider
import maru.consensus.blockimport.TransactionalSealedBeaconBlockImporter
import maru.consensus.blockimport.ValidatingSealedBeaconBlockImporter
import maru.consensus.qbft.ProposerSelectorImpl
import maru.consensus.qbft.QbftConsensusFollower
import maru.consensus.state.FinalizationProvider
import maru.consensus.state.StateTransitionImpl
import maru.consensus.validation.BeaconBlockValidatorFactoryImpl
import maru.consensus.validation.QuorumOfSealsVerifier
import maru.consensus.validation.SCEP256SealVerifier
import maru.core.Protocol
import maru.database.BeaconChain
import maru.executionlayer.ExecutionLayerFactory.buildExecutionLayerManager
import maru.p2p.P2PNetwork
import net.consensys.linea.metrics.MetricsFacade
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JClient
import tech.pegasys.teku.infrastructure.async.SafeFuture

class QbftFollowerFactory(
  private val p2pNetwork: P2PNetwork,
  private val beaconChain: BeaconChain,
  private val validatorELNodeEngineApiWeb3JClient: Web3JClient?,
  private val followerELNodeEngineApiWeb3JClients: Map<String, Web3JClient>,
  private val metricsFacade: MetricsFacade,
  private val allowEmptyBlocks: Boolean,
  private val finalizationStateProvider: FinalizationProvider,
  private val payloadValidationEnabled: Boolean,
) : ProtocolFactory {
  override fun create(forkSpec: ForkSpec): Protocol {
    val qbftConsensusConfig = (forkSpec.configuration as QbftConsensusConfig)

    val elManager =
      validatorELNodeEngineApiWeb3JClient?.let {
        buildExecutionLayerManager(
          web3JEngineApiClient = it,
          elFork = qbftConsensusConfig.elFork,
          metricsFacade = metricsFacade,
        )
      }

    val validatorProvider = StaticValidatorProvider(validators = qbftConsensusConfig.validatorSet)
    val stateTransition = StateTransitionImpl(validatorProvider)

    val transactionalSealedBeaconBlockImporter =
      TransactionalSealedBeaconBlockImporter(beaconChain, stateTransition) { _, _ ->
        SafeFuture.completedFuture(Unit)
      }

    val beaconBlockValidatorFactory =
      BeaconBlockValidatorFactoryImpl(
        beaconChain = beaconChain,
        proposerSelector = ProposerSelectorImpl,
        stateTransition = stateTransition,
        executionLayerManager = if (payloadValidationEnabled) elManager else null,
        allowEmptyBlocks = allowEmptyBlocks,
      )

    val sealsVerifier = QuorumOfSealsVerifier(validatorProvider, SCEP256SealVerifier())
    val payloadValidatorNewBlockImporter =
      ValidatingSealedBeaconBlockImporter(
        beaconBlockImporter = transactionalSealedBeaconBlockImporter,
        sealsVerifier = sealsVerifier,
        beaconBlockValidatorFactory = beaconBlockValidatorFactory,
      )

    // Subscribe EL driving outside of the follower — fires for all blocks
    // (P2P-received and CL-synced), driving both this node's EL and any follower ELs.
    return QbftConsensusFollower(p2pNetwork, payloadValidatorNewBlockImporter).subscribeElSync(
      beaconChain,
      Helpers.buildElImporterHandlers(
        ownExecutionLayerManager = elManager,
        ownHandlerName = "follower-el",
        followerELNodeEngineApiWeb3JClients = followerELNodeEngineApiWeb3JClients,
        elFork = qbftConsensusConfig.elFork,
        finalizationStateProvider = finalizationStateProvider,
        metricsFacade = metricsFacade,
      ),
    )
  }
}
