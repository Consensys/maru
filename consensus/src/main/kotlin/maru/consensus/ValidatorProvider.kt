/*
   Copyright 2025 Consensys Software Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package maru.consensus

import kotlin.collections.setOf
import maru.core.Validator

/**
 * Provides access to the set of validators for a given block.
 */
interface ValidatorProvider {
  fun getValidatorsAfterBlock(blockNumber: ULong): Set<Validator>

  fun getValidatorsForBlock(blockNumber: ULong): Set<Validator>
}

/**
 * A [ValidatorProvider] that always returns the same [Validator] instance. This is useful for the single validator case.
 */
class StaticValidatorProvider(
  private val validator: Validator,
) : ValidatorProvider {
  override fun getValidatorsAfterBlock(blockNumber: ULong): Set<Validator> = setOf(validator)

  override fun getValidatorsForBlock(blockNumber: ULong): Set<Validator> = setOf(validator)
}
