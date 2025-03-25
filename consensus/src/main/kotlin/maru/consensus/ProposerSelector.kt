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

import maru.core.Validator
import maru.database.BeaconChain
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.blockcreation.BftProposerSelector.selectProposerForRound
import org.hyperledger.besu.datatypes.Address
import tech.pegasys.teku.infrastructure.async.SafeFuture

interface ProposerSelector {
  fun selectProposerForRound(roundIdentifier: ConsensusRoundIdentifier): SafeFuture<Validator>
}

class ProposerSelectorImpl(
  private val beaconChain: BeaconChain,
  private val validatorProvider: ValidatorProvider,
) : ProposerSelector {
  override fun selectProposerForRound(roundIdentifier: ConsensusRoundIdentifier): SafeFuture<Validator> {
    val prevBlockNumber = roundIdentifier.sequenceNumber - 1
    val parentBlock =
      beaconChain.getSealedBeaconBlock(prevBlockNumber.toULong())
        ?: return SafeFuture.failedFuture(IllegalStateException("Parent state not found"))
    val parentBlockHeader = parentBlock.beaconBlock.beaconBlockHeader
    val prevBlockProposer = Address.wrap(Bytes.wrap(parentBlockHeader.proposer.address))

    val validatorsForRound =
      validatorProvider.getValidatorsForBlock(parentBlockHeader).get().map {
        Address.wrap(Bytes.wrap(it.address))
      }
    val proposer = selectProposerForRound(roundIdentifier, prevBlockProposer, validatorsForRound, true)
    return SafeFuture.completedFuture(Validator(proposer.toArray()))
  }
}
