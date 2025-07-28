/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.sync

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import maru.consensus.StaticValidatorProvider
import maru.consensus.blockimport.TransactionalSealedBeaconBlockImporter
import maru.consensus.blockimport.ValidatingSealedBeaconBlockImporter
import maru.consensus.qbft.ProposerSelectorImpl
import maru.consensus.state.StateTransitionImpl
import maru.consensus.validation.BeaconBlockValidatorFactory
import maru.consensus.validation.BlockNumberValidator
import maru.consensus.validation.BodyRootValidator
import maru.consensus.validation.CompositeBlockValidator
import maru.consensus.validation.EmptyBlockValidator
import maru.consensus.validation.ParentRootValidator
import maru.consensus.validation.ProposerValidator
import maru.consensus.validation.QuorumOfSealsVerifier
import maru.consensus.validation.SCEP256SealVerifier
import maru.consensus.validation.StateRootValidator
import maru.consensus.validation.TimestampValidator
import maru.core.BeaconBlockHeader
import maru.core.Validator
import maru.database.BeaconChain
import maru.p2p.PeerLookup
import maru.sync.pipeline.BeaconChainDownloadPipelineFactory
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.services.pipeline.Pipeline
import tech.pegasys.teku.infrastructure.async.SafeFuture

class SyncService(
  private val peerLookup: PeerLookup,
  private val beaconChain: BeaconChain,
  private val validators: Set<Validator>,
  private val executorService: ExecutorService = Executors.newCachedThreadPool(),
  private val allowEmptyBlocks: Boolean = true,
) {
  private val log: Logger = LogManager.getLogger(this::class.java)
  private var pipeline: Pipeline<*>? = null

  fun start(): SafeFuture<Void> {
    log.info("Starting SyncService")
    val blockImporter = createSealedBeaconBlockImporter()
    val pipelineFactory = BeaconChainDownloadPipelineFactory(beaconChain, blockImporter)
    pipeline = pipelineFactory.createPipeline(peerLookup)
    pipeline?.start(executorService)
    return SafeFuture.completedFuture(null)
  }

  fun stop() {
    // TODO should the executor service be stopped here?
    log.info("Stopping SyncService")
    pipeline?.abort()
    log.info("SyncService stopped")
  }

  private fun createSealedBeaconBlockImporter(): ValidatingSealedBeaconBlockImporter {
    val validatorProvider = StaticValidatorProvider(validators = validators.toSet())
    val stateTransition = StateTransitionImpl(validatorProvider)
    val sealsVerifier = QuorumOfSealsVerifier(validatorProvider, SCEP256SealVerifier())

    // Create custom BeaconBlockValidatorFactory without ExecutionPayloadValidator
    val beaconBlockValidatorFactory = createBeaconBlockValidatorFactory()

    // Create TransactionalSealedBeaconBlockImporter without BeaconBlockImporter
    // We'll handle state transitions and database commits directly
    val transactionalSealedBeaconBlockImporter =
      TransactionalSealedBeaconBlockImporter(
        beaconChain = beaconChain,
        stateTransition = stateTransition,
        beaconBlockImporter = { _, _ ->
          SafeFuture.completedFuture(Unit)
        },
      )

    // Create ValidatingSealedBeaconBlockImporter
    val validatingSealedBeaconBlockImporter =
      ValidatingSealedBeaconBlockImporter(
        sealsVerifier = sealsVerifier,
        beaconBlockImporter = transactionalSealedBeaconBlockImporter,
        beaconBlockValidatorFactory = beaconBlockValidatorFactory,
      )
    return validatingSealedBeaconBlockImporter
  }

  private fun createBeaconBlockValidatorFactory(): BeaconBlockValidatorFactory {
    // Create a custom BeaconBlockValidatorFactory that excludes ExecutionPayloadValidator
    return BeaconBlockValidatorFactory { beaconBlockHeader: BeaconBlockHeader ->
      val parentBeaconBlockNumber = beaconBlockHeader.number - 1UL
      val parentBlock =
        beaconChain.getSealedBeaconBlock(parentBeaconBlockNumber)
          ?: throw IllegalArgumentException("Expected block for header number=$parentBeaconBlockNumber isn't found!")

      val parentHeader = parentBlock.beaconBlock.beaconBlockHeader

      CompositeBlockValidator(
        blockValidators =
          listOfNotNull(
            StateRootValidator(StateTransitionImpl(StaticValidatorProvider(validators.toSet()))),
            BlockNumberValidator(parentHeader),
            TimestampValidator(parentHeader),
            ProposerValidator(ProposerSelectorImpl, beaconChain),
            ParentRootValidator(parentHeader),
            BodyRootValidator(),
            // Excluded ExecutionPayloadValidator
            if (!allowEmptyBlocks) EmptyBlockValidator else null,
          ),
      )
    }
  }
}
