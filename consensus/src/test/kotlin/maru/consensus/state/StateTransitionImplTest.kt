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
package maru.consensus.state

import com.github.michaelbull.result.Ok
import maru.consensus.ProposerSelector
import maru.consensus.ValidatorProvider
import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.HashUtil
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.database.BeaconChain
import maru.serialization.rlp.bodyRoot
import maru.serialization.rlp.stateRoot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.doReturn
import tech.pegasys.teku.infrastructure.async.SafeFuture

class StateTransitionImplTest {
  private val currentBlock = DataGenerators.randomBeaconBlock(9u)
  private val preState =
    BeaconState(
      latestBeaconBlockHeader = currentBlock.beaconBlockHeader,
      latestBeaconBlockRoot = currentBlock.beaconBlockHeader.bodyRoot,
      validators = setOf(currentBlock.beaconBlockHeader.proposer),
    )

  private val newBeaconBlockBody = DataGenerators.randomBeaconBlockBody()
  private val tmpBlockHeader =
    DataGenerators
      .randomBeaconBlockHeader(currentBlock.beaconBlockHeader.number + 1u)
      .copy(
        parentRoot = currentBlock.beaconBlockHeader.hash,
        stateRoot = ByteArray(0),
        bodyRoot = HashUtil.bodyRoot(newBeaconBlockBody),
      )
  private val tmpPostState =
    BeaconState(
      latestBeaconBlockHeader = tmpBlockHeader,
      latestBeaconBlockRoot = tmpBlockHeader.bodyRoot,
      validators = setOf(tmpBlockHeader.proposer),
    )
  private val stateRootHash = HashUtil.stateRoot(tmpPostState)
  private val newBlockHeader = tmpBlockHeader.copy(stateRoot = stateRootHash)
  private val newBlock = BeaconBlock(newBlockHeader, newBeaconBlockBody)
  private val newBlockValidators = setOf(newBlockHeader.proposer)
  private val postState =
    BeaconState(
      latestBeaconBlockHeader = newBlockHeader,
      latestBeaconBlockRoot = newBlockHeader.bodyRoot,
      validators = newBlockValidators,
    )

  private lateinit var beaconChain: BeaconChain
  private val validatorProvider: ValidatorProvider =
    object : ValidatorProvider {
      override fun getValidatorsForBlock(blockNumber: ULong): SafeFuture<Set<Validator>> {
        val validator =
          when (blockNumber) {
            currentBlock.beaconBlockHeader.number -> setOf(currentBlock.beaconBlockHeader.proposer)
            newBlockHeader.number -> setOf(newBlockHeader.proposer)
            else -> setOf(DataGenerators.randomValidator())
          }
        return SafeFuture.completedFuture(validator)
      }
    }
  private val proposerSelector: ProposerSelector =
    object : ProposerSelector {
      override fun getProposerForBlock(header: BeaconBlockHeader): SafeFuture<Validator> {
        val proposer =
          when (header.number) {
            currentBlock.beaconBlockHeader.number -> currentBlock.beaconBlockHeader.proposer
            newBlockHeader.number -> newBlockHeader.proposer
            else -> DataGenerators.randomValidator()
          }
        return SafeFuture.completedFuture(proposer)
      }
    }

  @BeforeEach
  fun setup() {
    beaconChain = mock()
    doReturn(preState).`when`(beaconChain).getLatestBeaconState()
  }

  @Test
  fun `processBlock should return ok valid state transition`() {
    val stateTransition =
      StateTransitionImpl(
        validatorProvider = validatorProvider,
        proposerSelector = proposerSelector,
        beaconChain = beaconChain,
      )

    val result = stateTransition.processBlock(newBlock).get()
    val expectedResult = Ok(postState)
    assertThat(result).isEqualTo(expectedResult)
  }

  @Test
  fun `processBlock should return Err for invalid state transition`() {
    val stateTransition =
      StateTransitionImpl(
        validatorProvider = validatorProvider,
        proposerSelector = proposerSelector,
        beaconChain = beaconChain,
      )

    val invalidBlockHeader = newBlockHeader.copy(number = newBlockHeader.number + 1u)
    val invalidBlock = BeaconBlock(invalidBlockHeader, newBeaconBlockBody)
    val result = stateTransition.processBlock(invalidBlock).get()
    assertThat(result.component2()?.message?.contains("Beacon state root does not match")).isTrue()
  }
}
