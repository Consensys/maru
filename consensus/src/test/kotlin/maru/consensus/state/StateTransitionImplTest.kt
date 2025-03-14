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

import maru.consensus.validation.BlockValidationException
import maru.consensus.validation.BlockValidator
import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import maru.core.HashUtil
import maru.core.ext.DataGenerators
import maru.serialization.rlp.bodyRoot
import maru.serialization.rlp.stateRoot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture

class StateTransitionImplTest {
  @Test
  fun `processBlock should return failure if block validation fails`() {
    val currentBlock = DataGenerators.randomBeaconBlock(9u)
    val preState =
      DataGenerators.randomBeaconState(9u).copy(
        latestBeaconBlockHeader = currentBlock.beaconBlockHeader,
        latestBeaconBlockRoot = HashUtil.bodyRoot(currentBlock.beaconBlockBody),
      )

    val newBeaconBlockBody = DataGenerators.randomBeaconBlockBody()
    val stateRootBlockHeader =
      DataGenerators.randomBeaconBlockHeader(10u).copy(
        parentRoot = currentBlock.beaconBlockHeader.hash,
        stateRoot = ByteArray(0),
        bodyRoot = HashUtil.bodyRoot(newBeaconBlockBody),
      )
    val stateRootHash =
      HashUtil.stateRoot(
        preState.copy(
          latestBeaconBlockHeader = stateRootBlockHeader,
          latestBeaconBlockRoot = stateRootBlockHeader.bodyRoot,
        ),
      )
    val newBlockHeader = stateRootBlockHeader.copy(stateRoot = stateRootHash)
    val newBlock = BeaconBlock(newBlockHeader, newBeaconBlockBody)

    val blockValidator = mock<BlockValidator>()
    whenever(blockValidator.validateBlock(any(), any()))
      .thenAnswer {
        val block = it.getArgument<BeaconBlock>(0)
        val prevBlockHeader = it.getArgument<BeaconBlockHeader>(1)
        if (!(block == newBlock && prevBlockHeader == preState.latestBeaconBlockHeader)) {
          SafeFuture.completedFuture(Result.success(true))
        } else {
          SafeFuture.completedFuture(Result.failure<Boolean>(BlockValidationException("Block validation failed")))
        }
      }
    val stateTransition = StateTransitionImpl(blockValidator)

    val result = stateTransition.processBlock(newBlock, preState).get()
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull())
      .isInstanceOf(StateTransitionException::class.java)
      .hasMessage("State Transition failed: Block validation failed, cause: Block validation failed")
  }
}
