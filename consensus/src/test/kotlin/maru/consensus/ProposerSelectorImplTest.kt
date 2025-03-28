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

import java.util.TreeSet
import java.util.concurrent.ExecutionException
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.database.BeaconChain
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture

class ProposerSelectorImplTest {
  private val totalValidators = 5
  private val validators = List(totalValidators) { DataGenerators.randomValidator() }.toSet()
  private val genesisBlockNumber = 10uL
  private val genesisValidator = validators.first()
  private val validatorsAddresses = TreeSet(validators.map { it.toAddress() })

  private val validatorProvider =
    object : ValidatorProvider {
      override fun getValidatorsForBlock(blockNumber: ULong): SafeFuture<Set<Validator>> =
        SafeFuture.completedFuture(validators)
    }

  @Test
  fun `select proposer for genesis block`() {
    val beaconChain = mock<BeaconChain>()
    val config =
      ProposerSelectorImpl.Config(
        changeEachBlock = true,
        genesisBlockNumber = genesisBlockNumber,
        genesisBlockValidator = genesisValidator,
      )
    val proposerSelector = ProposerSelectorImpl(beaconChain, validatorProvider, config)
    val consensusRoundIdentifier = ConsensusRoundIdentifier(genesisBlockNumber.toLong(), 0)

    val result = proposerSelector.getProposerForBlock(consensusRoundIdentifier).get()
    assertThat(result).isEqualTo(genesisValidator)
  }

  @Test
  fun `select proposer for next block`() {
    val config =
      ProposerSelectorImpl.Config(
        changeEachBlock = true,
        genesisBlockNumber = genesisBlockNumber,
        genesisBlockValidator = genesisValidator,
      )
    var prevProposer = genesisValidator
    val returnedValidators = mutableSetOf<Validator>()
    for (blockNumber in genesisBlockNumber + 1uL..genesisBlockNumber + totalValidators.toULong()) {
      val beaconChain = mock<BeaconChain>()
      val randomBeaconBlock = DataGenerators.randomBeaconBlock(blockNumber)
      val prevBlock =
        randomBeaconBlock.copy(
          beaconBlockHeader = randomBeaconBlock.beaconBlockHeader.copy(proposer = prevProposer),
        )
      val prevSealedBlock = SealedBeaconBlock(prevBlock, emptyList())
      whenever(beaconChain.getSealedBeaconBlock(anyLong().toULong())).thenReturn(prevSealedBlock)

      val proposerSelector = ProposerSelectorImpl(beaconChain, validatorProvider, config)
      val consensusRoundIdentifier = ConsensusRoundIdentifier(blockNumber.toLong(), 0)

      val result = proposerSelector.getProposerForBlock(consensusRoundIdentifier).get()
      assertThat(result in validators).isTrue()
      prevProposer = result
      returnedValidators.add(result)
    }
    assertThat(returnedValidators).isEqualTo(validators)
  }

  @Test
  fun `test previous block not found`() {
    val config =
      ProposerSelectorImpl.Config(
        changeEachBlock = true,
        genesisBlockNumber = genesisBlockNumber,
        genesisBlockValidator = genesisValidator,
      )
    val blockNumber = genesisBlockNumber + 2uL
    val beaconChain = mock<BeaconChain>()
    whenever(beaconChain.getSealedBeaconBlock(anyLong().toULong())).thenReturn(null)

    val proposerSelector = ProposerSelectorImpl(beaconChain, validatorProvider, config)
    val consensusRoundIdentifier = ConsensusRoundIdentifier(blockNumber.toLong(), 0)

    val exception =
      assertThrows<ExecutionException> {
        proposerSelector.getProposerForBlock(consensusRoundIdentifier).get()
      }
    assertThat(exception.cause).isInstanceOf(IllegalArgumentException::class.java)
    assertThat(exception.cause?.message).isEqualTo("Parent block not found. parentBlockNumber=${blockNumber - 1uL}")
  }
}
