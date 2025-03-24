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

import maru.consensus.ValidatorProvider
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockHeader
import org.hyperledger.besu.consensus.qbft.core.types.QbftValidatorProvider
import org.hyperledger.besu.datatypes.Address

/**
 * Adapter to convert a [ValidatorProvider] to a [QbftValidatorProvider].
 */
class QbftValidatorProviderAdaptor(
  private val validatorProvider: ValidatorProvider,
) : QbftValidatorProvider {
  // This method has been removed from QbftValidatorProvider in Besu and will be removed once Besu version is updated
  override fun getValidatorsAtHead(): Collection<Address> = throw UnsupportedOperationException("Not implemented")

  override fun getValidatorsAfterBlock(header: QbftBlockHeader): Collection<Address> =
    validatorProvider.getValidatorsAfterBlock(header.toBeaconBlockHeader().number).get().map {
      Address.wrap(Bytes.wrap(it.address))
    }

  override fun getValidatorsForBlock(header: QbftBlockHeader): Collection<Address> =
    validatorProvider.getValidatorsForBlock(header.toBeaconBlockHeader().number).get().map {
      Address.wrap(Bytes.wrap(it.address))
    }
}
