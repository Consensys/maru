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
package maru.consensus.dummy

import java.time.Clock
import kotlin.random.Random
import maru.core.ExecutionPayload
import maru.executionlayer.manager.DataGenerators
import maru.executionlayer.manager.ExecutionLayerManager
import maru.executionlayer.manager.ForkChoiceUpdatedResult
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture

class EngineApiBlockCreatorTest {
  private lateinit var executionLayerManager: ExecutionLayerManager

  @BeforeEach
  fun setUp() {
    executionLayerManager = Mockito.mock()
  }

  @AfterEach
  fun tearDown() {
    Mockito.reset(executionLayerManager)
  }

  private fun createDummyConsensusState(finalizationState: FinalizationState): DummyConsensusState =
    DummyConsensusState(
      Clock.systemUTC(),
      finalizationState,
      Random.Default.nextBytes(32),
    )

  private fun mockFinishBlockBuilding(result: ExecutionPayload) {
    whenever(executionLayerManager.finishBlockBuilding()).thenReturn(
      SafeFuture.completedFuture(result),
    )
  }

  private fun mockSetHeadAndStartBlockBuilding(result: ForkChoiceUpdatedResult) {
    whenever(executionLayerManager.setHeadAndStartBlockBuilding(any(), any(), any(), any())).thenReturn(
      SafeFuture.completedFuture(result),
    )
  }

  @Test
  fun `initialization triggers setHeadAndStartBlockBuilding with latest known state`() {
    val finalizationState = FinalizationState(Random.Default.nextBytes(32), Random.Default.nextBytes(32))
    val dummyConsensusState = createDummyConsensusState(finalizationState)
    mockSetHeadAndStartBlockBuilding(DataGenerators.randomValidForkChoiceUpdatedResult())
    val nextTimestamp = dummyConsensusState.clock.millis()
    EngineApiBlockCreator(
      manager = executionLayerManager,
      state = dummyConsensusState,
      blockHeaderFunctions = MainnetBlockHeaderFunctions(),
      initialBlockTimestamp = nextTimestamp,
    )

    Mockito.verify(executionLayerManager, Mockito.atLeastOnce()).setHeadAndStartBlockBuilding(
      eq(dummyConsensusState.latestBlockHash),
      eq(finalizationState.safeBlockHash),
      eq(finalizationState.finalizedBlockHash),
      eq(nextTimestamp),
    )
  }

  @Test
  fun `block creator doesn't call setHeadAndStartBlockBuilding or finishBlockBuilding twice in a row`() {
    val finalizationState = FinalizationState(Random.Default.nextBytes(32), Random.Default.nextBytes(32))
    val dummyConsensusState = createDummyConsensusState(finalizationState)
    val expectedBlockBuildingResult =
      maru.core.ext.DataGenerators
        .randomExecutionPayload()
    mockFinishBlockBuilding(expectedBlockBuildingResult)
    val forkChoiceUpdatedResult = DataGenerators.randomValidForkChoiceUpdatedResult()
    mockSetHeadAndStartBlockBuilding(forkChoiceUpdatedResult)
    val nextTimestamp = dummyConsensusState.clock.millis()

    val blockCreator =
      EngineApiBlockCreator(
        manager = executionLayerManager,
        state = dummyConsensusState,
        blockHeaderFunctions = MainnetBlockHeaderFunctions(),
        initialBlockTimestamp = nextTimestamp,
      )
    blockCreator.createEmptyWithdrawalsBlock(1L, null)
    blockCreator.createEmptyWithdrawalsBlock(2L, null)
    blockCreator.createEmptyWithdrawalsBlock(3L, null)

    val inOrder = Mockito.inOrder(executionLayerManager)
    repeat((1..3).count()) {
      inOrder.verify(executionLayerManager).setHeadAndStartBlockBuilding(
        any(),
        any(),
        any(),
        any(),
      )
      inOrder.verify(executionLayerManager).finishBlockBuilding()
    }
    inOrder.verify(executionLayerManager).setHeadAndStartBlockBuilding(
      any(),
      any(),
      any(),
      any(),
    )
    Mockito.verifyNoMoreInteractions(executionLayerManager)
  }

  @Test
  fun `finalization updates are respected`() {
    val finalizationState = FinalizationState(Random.Default.nextBytes(32), Random.Default.nextBytes(32))
    val dummyConsensusState = createDummyConsensusState(finalizationState)
    val expectedBlockBuildingResult =
      maru.core.ext.DataGenerators
        .randomExecutionPayload()
    mockFinishBlockBuilding(expectedBlockBuildingResult)
    val forkChoiceUpdatedResult = DataGenerators.randomValidForkChoiceUpdatedResult()
    mockSetHeadAndStartBlockBuilding(forkChoiceUpdatedResult)
    val initialTimestamp = dummyConsensusState.clock.millis()

    val blockCreator =
      EngineApiBlockCreator(
        manager = executionLayerManager,
        state = dummyConsensusState,
        blockHeaderFunctions = MainnetBlockHeaderFunctions(),
        initialBlockTimestamp = initialTimestamp,
      )
    val nextTimestamp1 = 123L
    blockCreator.createEmptyWithdrawalsBlock(nextTimestamp1, null)
    Mockito.verify(executionLayerManager, Mockito.atLeastOnce()).setHeadAndStartBlockBuilding(
      eq(expectedBlockBuildingResult.blockHash),
      eq(finalizationState.safeBlockHash),
      eq(finalizationState.finalizedBlockHash),
      eq(nextTimestamp1),
    )
    val newFinalizationState = FinalizationState(Random.Default.nextBytes(32), Random.Default.nextBytes(32))
    dummyConsensusState.updateFinalizationState(newFinalizationState)

    val otherTimestamp2 = 124L
    blockCreator.createEmptyWithdrawalsBlock(otherTimestamp2, null)

    Mockito.verify(executionLayerManager, Mockito.atLeastOnce()).setHeadAndStartBlockBuilding(
      eq(expectedBlockBuildingResult.blockHash),
      eq(newFinalizationState.safeBlockHash),
      eq(newFinalizationState.finalizedBlockHash),
      eq(otherTimestamp2),
    )
  }
}
