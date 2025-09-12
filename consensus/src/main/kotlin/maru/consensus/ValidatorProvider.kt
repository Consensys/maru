/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus

import maru.consensus.qbft.sortedByAddress
import maru.core.Validator
import org.apache.logging.log4j.LogManager
import tech.pegasys.teku.infrastructure.async.SafeFuture

/**
 * Provides access to the set of validators for a given block.
 */
interface ValidatorProvider {
  fun getValidatorsAfterBlock(blockNumber: ULong): SafeFuture<Set<Validator>> = getValidatorsForBlock(blockNumber + 1u)

  fun getValidatorsForBlock(blockNumber: ULong): SafeFuture<Set<Validator>>
}

/**
 * A [ValidatorProvider] that always returns the same [Validator] instance. This is useful for the single validator case.
 */
class StaticValidatorProvider(
  validators: Set<Validator>,
) : ValidatorProvider {
  private val log = LogManager.getLogger(this.javaClass)

  private val validators: Set<Validator> =
    validators.sortedByAddress()

  override fun getValidatorsForBlock(blockNumber: ULong): SafeFuture<Set<Validator>> {
    log.debug("Providing static validators for block {}: {}", blockNumber, validators)
    return SafeFuture.completedFuture(validators)
  }
}
