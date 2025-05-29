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
package maru.consensus.blockimport

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import maru.consensus.validation.BeaconBlockValidatorFactory
import maru.consensus.validation.BlockValidator
import maru.consensus.validation.SealsVerifier
import maru.core.ext.DataGenerators
import maru.p2p.ValidationResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tech.pegasys.teku.infrastructure.async.SafeFuture

class ValidatingSealedBeaconBlockImporterTest {
  private val blockHeader = DataGenerators.randomBeaconBlockHeader(1u)
  private val beaconBlock = DataGenerators.randomBeaconBlock(blockHeader.number)
  private val sealedBeaconBlock = DataGenerators.randomSealedBeaconBlock(beaconBlock.beaconBlockHeader.number)

  private fun blockValidator(result: Result<Unit, BlockValidator.BlockValidationError>) =
    object : BlockValidator {
      override fun validateBlock(block: maru.core.BeaconBlock) = SafeFuture.completedFuture(result)
    }

  @Test
  fun `importBlock succeeds when seals and block are valid`() {
    val sealsVerifier = SealsVerifier { _, _ -> SafeFuture.completedFuture(Ok(Unit)) }
    val blockValidatorFactory = BeaconBlockValidatorFactory { blockValidator(Ok(Unit)) }
    val beaconBlockImporter =
      SealedBeaconBlockImporter { _ ->
        SafeFuture.completedFuture(
          ValidationResult.Companion.Valid as ValidationResult,
        )
      }

    val importer = ValidatingSealedBeaconBlockImporter(sealsVerifier, beaconBlockImporter, blockValidatorFactory)
    val result = importer.importBlock(sealedBeaconBlock).get()
    assertThat(result).isEqualTo(ValidationResult.Companion.Valid)
  }

  @Test
  fun `importBlock returns Err when seal verification fails`() {
    val sealsVerifier = SealsVerifier { _, _ -> SafeFuture.completedFuture(Err("seal error")) }
    val blockValidatorFactory = BeaconBlockValidatorFactory { blockValidator(Ok(Unit)) }
    var called = false
    val expectedValidationResult: ValidationResult = ValidationResult.Companion.Invalid("seal error")
    val beaconBlockImporter =
      SealedBeaconBlockImporter { _ ->
        called = true
        SafeFuture.completedFuture(expectedValidationResult)
      }

    val importer = ValidatingSealedBeaconBlockImporter(sealsVerifier, beaconBlockImporter, blockValidatorFactory)
    val result = importer.importBlock(sealedBeaconBlock).get()
    assertThat(result).isEqualTo(expectedValidationResult)
    assertThat(called).isFalse()
  }

  @Test
  fun `importBlock returns Err when block validation fails`() {
    val sealsVerifier = SealsVerifier { _, _ -> SafeFuture.completedFuture(Ok(Unit)) }
    val blockValidatorFactory =
      BeaconBlockValidatorFactory {
        blockValidator(Err(BlockValidator.BlockValidationError("block error")))
      }
    var called = false
    val beaconBlockImporter =
      SealedBeaconBlockImporter { _ ->
        called = true
        SafeFuture.completedFuture(ValidationResult.Companion.Valid as ValidationResult)
      }

    val importer = ValidatingSealedBeaconBlockImporter(sealsVerifier, beaconBlockImporter, blockValidatorFactory)
    val result = importer.importBlock(sealedBeaconBlock).get()
    assertThat(result).isEqualTo(ValidationResult.Companion.Invalid("block error"))
    assertThat(called).isFalse()
  }

  @Test
  fun `importBlock handles exception and does not throw`() {
    val sealsVerifier = SealsVerifier { _, _ -> throw RuntimeException("fail") }
    val blockValidatorFactory = BeaconBlockValidatorFactory { blockValidator(Ok(Unit)) }
    val beaconBlockImporter =
      SealedBeaconBlockImporter { _ ->
        SafeFuture.completedFuture(
          ValidationResult.Companion
            .Valid as ValidationResult,
        )
      }

    val importer = ValidatingSealedBeaconBlockImporter(sealsVerifier, beaconBlockImporter, blockValidatorFactory)
    assertThatThrownBy { importer.importBlock(sealedBeaconBlock).get() }
      .isInstanceOf(RuntimeException::class.java)
      .hasMessage("fail")
  }
}
