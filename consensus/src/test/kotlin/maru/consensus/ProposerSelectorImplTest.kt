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

import maru.consensus.qbft.ProposerSelectorImpl
import maru.core.BeaconState
import maru.core.Validator
import maru.core.ext.DataGenerators
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.junit.jupiter.api.Test

class ProposerSelectorImplTest {
  private val totalValidators = 5
  private val orderedValidators = List(totalValidators) { DataGenerators.randomValidator() }
  private val validators = orderedValidators.toSet()
  private val genesisBlockNumber = 10uL

  @Test
  fun `select proposer for next block, new blocks, same round`() {
        /*
          The proposer should change for each block.
          We test for the first 5 blocks, same as number of validators in the test.
          So after 5 blocks each validator should have been selected once.
         */
    val proposerSelector = ProposerSelectorImpl

    val returnedValidators = mutableSetOf<Validator>()

    for (blockNumber in genesisBlockNumber + 1uL..genesisBlockNumber + totalValidators.toULong()) {
      val previousBlockNumber = blockNumber - 1uL
      val beaconState =
        generateStateWithValidators(
          previousBlockNumber,
          validators,
          orderedValidators[(previousBlockNumber - genesisBlockNumber).toInt()],
        )
      val consensusRoundIdentifier = ConsensusRoundIdentifier(blockNumber.toLong(), 0)
      val result = proposerSelector.getProposerForBlock(beaconState, consensusRoundIdentifier).get()
      assertThat(result in validators).isTrue()
      returnedValidators.add(result)
    }

    assertThat(returnedValidators).isEqualTo(validators)
  }

  @Test
  fun `select proposer for next block, same block, new rounds`() {
        /*
          The proposer should change for each block and round
          We test for the first 5 rounds of the same block number, same as number of validators in the test.
          So after 5 rounds each validator should have been selected once.
         */
    val beaconState = generateStateWithValidators(genesisBlockNumber, validators)

    val proposerSelector = ProposerSelectorImpl

    val returnedValidators = mutableSetOf<Validator>()
    for (roundNumber in 0 until totalValidators) {
      val consensusRoundIdentifier = ConsensusRoundIdentifier((genesisBlockNumber + 1uL).toLong(), roundNumber)
      val result = proposerSelector.getProposerForBlock(beaconState, consensusRoundIdentifier).get()
      assertThat(result in validators).isTrue()
      returnedValidators.add(result)
    }

    assertThat(returnedValidators).isEqualTo(validators)
  }

  private fun generateStateWithValidators(
    expectedBlockNumber: ULong,
    expectedValidators: Set<Validator>,
    expectedProposer: Validator = expectedValidators.first(),
  ): BeaconState {
    val latestBlockHeader = DataGenerators.randomBeaconBlockHeader(expectedBlockNumber, proposer = expectedProposer)
    return BeaconState(latestBlockHeader, expectedValidators)
  }
}
