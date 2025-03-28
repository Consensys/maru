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

import maru.core.BeaconBlockHeader
import maru.core.Validator
import maru.database.BeaconChain
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.datatypes.Address
import tech.pegasys.teku.infrastructure.async.SafeFuture
import org.hyperledger.besu.consensus.common.bft.blockcreation.ProposerSelector as BesuProposerSelector

fun interface ProposerSelector {
  fun getProposerForBlock(consensusRoundIdentifier: ConsensusRoundIdentifier): SafeFuture<Validator>
}

class ProposerSelectorImpl(
  private val beaconChain: BeaconChain,
  private val validatorProvider: ValidatorProvider,
  private val config: Config,
) : ProposerSelector {
  data class Config(
    val changeEachBlock: Boolean,
    val genesisBlockNumber: ULong,
    val genesisBlockValidator: Validator,
  )

  override fun getProposerForBlock(consensusRoundIdentifier: ConsensusRoundIdentifier): SafeFuture<Validator> {
    val blockNumber = consensusRoundIdentifier.sequenceNumber.toULong()
    if (blockNumber == config.genesisBlockNumber) {
      return SafeFuture.completedFuture(config.genesisBlockValidator)
    }
    val prevBlockProposer =
      beaconChain
        .getSealedBeaconBlock(blockNumber - 1uL)
        ?.beaconBlock
        ?.beaconBlockHeader
        ?.proposer
        ?.toAddress()
        ?: return SafeFuture.failedFuture(
          IllegalArgumentException("Parent block not found. parentBlockNumber=${blockNumber - 1uL}"),
        )
    return validatorProvider.getValidatorsForBlock(blockNumber).thenApply { validators ->
      val proposerAddress =
        BesuProposerSelector.selectProposerForRound(
          consensusRoundIdentifier,
          prevBlockProposer,
          validators
            .stream()
            .map {
              it
                .toAddress()
            }.toList(),
          config.changeEachBlock,
        )
      Validator(proposerAddress.toArray())
    }
  }
}

fun BeaconBlockHeader.toConsensusRoundIdentifier(): ConsensusRoundIdentifier =
  ConsensusRoundIdentifier(this.number.toLong(), this.round.toInt())

fun Validator.toAddress(): Address = Address.wrap(Bytes.wrap(address))
