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
package maru.consensus.qbft.adapters

import com.github.michaelbull.result.Err
import java.util.Optional
import maru.consensus.validation.BlockValidator
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockValidator

class QbftBlockValidatorAdapter(
  private val blockValidator: BlockValidator,
) : QbftBlockValidator {
  override fun validateBlock(qbftBlock: QbftBlock): QbftBlockValidator.ValidationResult {
    val beaconBlock = qbftBlock.toBeaconBlock()
    val blockValidationResult = blockValidator.validateBlock(beaconBlock).get()
    if (blockValidationResult is Err) {
      return QbftBlockValidator.ValidationResult(
        false,
        Optional.of(blockValidationResult.error.toString()),
      )
    }
    return QbftBlockValidator.ValidationResult(true, Optional.empty())
  }
}
