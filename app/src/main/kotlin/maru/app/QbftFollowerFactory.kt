/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import maru.config.ValidatorElNode
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ForkSpec
import maru.consensus.NewBlockHandler
import maru.consensus.ProtocolFactory
import maru.consensus.StaticValidatorProvider
import maru.consensus.blockimport.TransactionalSealedBeaconBlockImporter
import maru.consensus.blockimport.ValidatingSealedBeaconBlockImporter
import maru.consensus.qbft.ProposerSelectorImpl
import maru.consensus.state.StateTransitionImpl
import maru.consensus.validation.BeaconBlockValidatorFactoryImpl
import maru.consensus.validation.QuorumOfSealsVerifier
import maru.consensus.validation.SCEP256SealVerifier
import maru.core.NoOpProtocol
import maru.core.Protocol
import maru.database.BeaconChain
import maru.executionlayer.manager.JsonRpcExecutionLayerManager
import maru.p2p.P2PNetwork
import net.consensys.linea.metrics.MetricsFacade
import tech.pegasys.teku.infrastructure.async.SafeFuture

class QbftFollowerFactory(
  private val p2pNetwork: P2PNetwork,
  private val beaconChain: BeaconChain,
  private val elPayloadValidatorNewBlockHandler: NewBlockHandler<*>,
  private val elFollowerNewBlockHandler: NewBlockHandler<*>,
  private val validatorElNodeConfig: ValidatorElNode,
  private val metricsFacade: MetricsFacade,
  private val allowEmptyBlocks: Boolean,
) : ProtocolFactory {
  override fun create(forkSpec: ForkSpec): Protocol {
    val qbftConsensusConfig = (forkSpec.configuration as QbftConsensusConfig)
    val validatorProvider = StaticValidatorProvider(validators = qbftConsensusConfig.validatorSet)
    val stateTransition = StateTransitionImpl(validatorProvider)
    val transactionalSealedBeaconBlockImporter =
      TransactionalSealedBeaconBlockImporter(beaconChain, stateTransition) { _, beaconBlock ->
        elPayloadValidatorNewBlockHandler
          .handleNewBlock(beaconBlock)
          .thenCompose {
            elFollowerNewBlockHandler.handleNewBlock(beaconBlock) // Don't wait for the result
            SafeFuture.completedFuture(Unit)
          }
      }
    val sealsVerifier = QuorumOfSealsVerifier(validatorProvider, SCEP256SealVerifier())
    val engineApiExecutionLayerClient =
      Helpers.buildExecutionEngineClient(
        validatorElNodeConfig.engineApiEndpoint,
        elFork = qbftConsensusConfig.elFork,
        metricsFacade = metricsFacade,
      )
    val executionLayerManager =
      JsonRpcExecutionLayerManager(
        executionLayerEngineApiClient = engineApiExecutionLayerClient,
      )
    val beaconBlockValidatorFactory =
      BeaconBlockValidatorFactoryImpl(
        beaconChain = beaconChain,
        proposerSelector = ProposerSelectorImpl,
        stateTransition = stateTransition,
        executionLayerManager = executionLayerManager,
        allowEmptyBlocks = allowEmptyBlocks,
      )
    val payloadValidatorNewBlockImporter =
      ValidatingSealedBeaconBlockImporter(
        beaconBlockImporter = transactionalSealedBeaconBlockImporter,
        sealsVerifier = sealsVerifier,
        beaconBlockValidatorFactory = beaconBlockValidatorFactory,
      )
    p2pNetwork.subscribeToBlocks(payloadValidatorNewBlockImporter::importBlock)

    return NoOpProtocol()
  }
}
