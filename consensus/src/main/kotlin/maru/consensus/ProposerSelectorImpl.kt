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

import kotlin.collections.map
import maru.core.BeaconState
import maru.core.Validator
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.blockcreation.BftProposerSelector
import org.hyperledger.besu.datatypes.Address
import tech.pegasys.teku.infrastructure.async.SafeFuture

fun interface ProposerSelector {
  fun getProposerForBlock(
    parentBeaconState: BeaconState,
    roundIdentifier: ConsensusRoundIdentifier,
  ): SafeFuture<Validator>
}

object ProposerSelectorImpl : ProposerSelector {
  override fun getProposerForBlock(
    parentBeaconState: BeaconState,
    roundIdentifier: ConsensusRoundIdentifier,
  ): SafeFuture<Validator> {
    val prevBlockProposer = Address.wrap(Bytes.wrap(parentBeaconState.latestBeaconBlockHeader.proposer.address))
    val validatorsForRound =
      parentBeaconState.validators.map {
        Address.wrap(Bytes.wrap(it.address))
      }
    val proposer =
      BftProposerSelector.selectProposerForRound(roundIdentifier, prevBlockProposer, validatorsForRound, true)
    return SafeFuture.completedFuture(Validator(proposer.toArray()))
  }
}
