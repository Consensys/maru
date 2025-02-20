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

import maru.consensus.qbft.BlockCreator
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import org.hyperledger.besu.consensus.common.bft.blockcreation.ProposerSelector
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockCreator
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockCreatorFactory
import org.hyperledger.besu.consensus.qbft.core.types.QbftValidatorProvider

class QbftBlockCreatorFactoryAdaptor(
  private val manager: ExecutionLayerManager,
  private val beaconChain: BeaconChain,
  private val proposerSelector: ProposerSelector,
  private val validatorProvider: QbftValidatorProvider,
) : QbftBlockCreatorFactory {
  override fun create(roundNumber: Int): QbftBlockCreator =
    BlockCreator(manager, beaconChain, proposerSelector, validatorProvider, roundNumber.toULong())
}
