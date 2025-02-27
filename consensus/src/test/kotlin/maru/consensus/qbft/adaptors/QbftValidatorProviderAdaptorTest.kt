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
package maru.consensus.qbft.adaptors

import kotlin.test.Test
import maru.consensus.ValidatorProvider
import maru.core.ext.DataGenerators
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.datatypes.Address
import org.mockito.Mockito
import org.mockito.kotlin.whenever

class QbftValidatorProviderAdaptorTest {
  @Test
  fun `can get validators at head`() {
    val validator = DataGenerators.randomValidator()
    val validatorProvider = Mockito.mock(ValidatorProvider::class.java)
    whenever(validatorProvider.getValidatorsAtHead()).thenReturn(setOf(validator))

    val qbftValidatorProviderAdaptor = QbftValidatorProviderAdaptor(validatorProvider)
    val validators = qbftValidatorProviderAdaptor.getValidatorsAtHead()
    assertThat(validators).containsExactly(Address.wrap(Bytes.wrap(validator.address)))
  }

  @Test
  fun `can get validators after block`() {
    val validatorProvider = Mockito.mock(ValidatorProvider::class.java)
    val validator1 = DataGenerators.randomValidator()
    val validator2 = DataGenerators.randomValidator()
    val header1 = QbftBlockHeaderAdaptor(DataGenerators.randomBeaconBlockHeader(10U))
    val header2 = QbftBlockHeaderAdaptor(DataGenerators.randomBeaconBlockHeader(11U))
    whenever(validatorProvider.getValidatorsAfterBlock(header1.beaconBlockHeader)).thenReturn(setOf(validator1))
    whenever(validatorProvider.getValidatorsAfterBlock(header2.beaconBlockHeader)).thenReturn(setOf(validator2))

    val qbftValidatorProviderAdaptor = QbftValidatorProviderAdaptor(validatorProvider)
    assertThat(
      qbftValidatorProviderAdaptor.getValidatorsAfterBlock(header1),
    ).containsExactly(Address.wrap(Bytes.wrap(validator1.address)))
    assertThat(
      qbftValidatorProviderAdaptor.getValidatorsAfterBlock(header2),
    ).containsExactly(Address.wrap(Bytes.wrap(validator2.address)))
  }

  @Test
  fun `can get validators for block`() {
    val validatorProvider = Mockito.mock(ValidatorProvider::class.java)
    val validator1 = DataGenerators.randomValidator()
    val validator2 = DataGenerators.randomValidator()
    val header1 = QbftBlockHeaderAdaptor(DataGenerators.randomBeaconBlockHeader(10U))
    val header2 = QbftBlockHeaderAdaptor(DataGenerators.randomBeaconBlockHeader(11U))
    whenever(validatorProvider.getValidatorsForBlock(header1.beaconBlockHeader)).thenReturn(setOf(validator1))
    whenever(validatorProvider.getValidatorsForBlock(header2.beaconBlockHeader)).thenReturn(setOf(validator2))

    val qbftValidatorProviderAdaptor = QbftValidatorProviderAdaptor(validatorProvider)
    assertThat(
      qbftValidatorProviderAdaptor.getValidatorsForBlock(header1),
    ).containsExactly(Address.wrap(Bytes.wrap(validator1.address)))
    assertThat(
      qbftValidatorProviderAdaptor.getValidatorsForBlock(header2),
    ).containsExactly(Address.wrap(Bytes.wrap(validator2.address)))
  }

  @Test
  fun `vote provider at head is empty`() {
    val validatorProvider = Mockito.mock(ValidatorProvider::class.java)
    val qbftValidatorProviderAdaptor = QbftValidatorProviderAdaptor(validatorProvider)
    assertThat(qbftValidatorProviderAdaptor.getVoteProviderAtHead().isEmpty).isTrue()
  }
}
