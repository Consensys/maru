/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing.beaconchain

import maru.consensus.StaticValidatorProvider
import maru.consensus.blockimport.SealedBeaconBlockImporter
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
import maru.p2p.ValidationResult
import tech.pegasys.teku.infrastructure.async.SafeFuture

class SyncSealedBlockImporterFactory {
  fun create(
    beaconChain: BeaconChain,
    validators: Set<Validator>,
    allowEmptyBlocks: Boolean = false,
  ): SealedBeaconBlockImporter<ValidationResult> {
    val validatorProvider = StaticValidatorProvider(validators = validators.toSet())
    val stateTransition = StateTransitionImpl(validatorProvider)
    val sealsVerifier = QuorumOfSealsVerifier(validatorProvider, SCEP256SealVerifier())

    // Create a custom BeaconBlockValidatorFactory without ExecutionPayloadValidator
    val beaconBlockValidatorFactory =
      createBeaconBlockValidatorFactory(beaconChain, validatorProvider, allowEmptyBlocks)

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

  private fun createBeaconBlockValidatorFactory(
    beaconChain: BeaconChain,
    validatorProvider: StaticValidatorProvider,
    allowEmptyBlocks: Boolean,
  ): BeaconBlockValidatorFactory {
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
            StateRootValidator(StateTransitionImpl(validatorProvider)),
            BlockNumberValidator(parentHeader),
            TimestampValidator(parentHeader),
            ProposerValidator(ProposerSelectorImpl, beaconChain),
            ParentRootValidator(parentHeader),
            BodyRootValidator(),
            if (!allowEmptyBlocks) EmptyBlockValidator else null,
          ),
      )
    }
  }
}
