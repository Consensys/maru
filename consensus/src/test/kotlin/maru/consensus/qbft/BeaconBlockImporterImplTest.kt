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
package maru.consensus.qbft

import kotlin.random.Random
import kotlin.test.assertEquals
import maru.consensus.state.FinalizationState
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.executionlayer.manager.ExecutionLayerManager
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture

class BeaconBlockImporterImplTest {
  private lateinit var executionLayerManager: ExecutionLayerManager
  private var nextBlockTimestamp: Long = 123456789L
  private var shouldBuildNextBlock: Boolean = false
  private var blockBuilderIdentity: Validator = Validator(Random.nextBytes(20))
  private lateinit var beaconBlockImporter: BeaconBlockImporterImpl
  private lateinit var finalizationState: FinalizationState

  @BeforeEach
  fun setUp() {
    executionLayerManager = mock(ExecutionLayerManager::class.java)
    finalizationState = FinalizationState(Random.nextBytes(32), Random.nextBytes(32))

    beaconBlockImporter =
      BeaconBlockImporterImpl(
        executionLayerManager,
        { finalizationState },
        { nextBlockTimestamp },
        { shouldBuildNextBlock },
        blockBuilderIdentity,
      )
  }

  @Test
  fun `importBlock should call setHeadAndStartBlockBuilding when shouldBuildNextBlock returns true`() {
    shouldBuildNextBlock = true
    val randomBeaconBlock = DataGenerators.randomBeaconBlock(1UL)

    val expectedResponse = SafeFuture.completedFuture(DataGenerators.randomValidForkChoiceUpdatedResult())
    whenever(
      executionLayerManager.setHeadAndStartBlockBuilding(
        eq(randomBeaconBlock.beaconBlockBody.executionPayload.blockHash),
        eq(finalizationState.safeBlockHash),
        eq(finalizationState.finalizedBlockHash),
        eq(nextBlockTimestamp),
        eq(blockBuilderIdentity.address),
      ),
    ).thenReturn(expectedResponse)

    val result = beaconBlockImporter.importBlock(randomBeaconBlock)
    assertEquals(expectedResponse, result)
    verify(executionLayerManager).setHeadAndStartBlockBuilding(
      eq(randomBeaconBlock.beaconBlockBody.executionPayload.blockHash),
      eq(finalizationState.safeBlockHash),
      eq(finalizationState.finalizedBlockHash),
      eq(nextBlockTimestamp),
      eq(blockBuilderIdentity.address),
    )
  }

  @Test
  fun `importBlock should call setHead when shouldBuildNextBlock returns false`() {
    val randomBeaconBlock = DataGenerators.randomBeaconBlock(1UL)

    val expectedResponse = SafeFuture.completedFuture(DataGenerators.randomValidForkChoiceUpdatedResult())
    whenever(
      executionLayerManager.setHead(
        eq(randomBeaconBlock.beaconBlockBody.executionPayload.blockHash),
        eq(finalizationState.safeBlockHash),
        eq(finalizationState.finalizedBlockHash),
      ),
    ).thenReturn(expectedResponse)

    val result = beaconBlockImporter.importBlock(randomBeaconBlock)
    assertEquals(expectedResponse, result)
    verify(executionLayerManager).setHead(
      eq(randomBeaconBlock.beaconBlockBody.executionPayload.blockHash),
      eq(finalizationState.safeBlockHash),
      eq(finalizationState.finalizedBlockHash),
    )
  }

  @Test
  fun `importBlock should pass next block's round identifier`() {
    val randomBeaconBlock = DataGenerators.randomBeaconBlock(1UL)
    val expectedConsensusRoundIdentifier = ConsensusRoundIdentifier(2, 0)
    val shouldBuildNextBlockPredicate: (ConsensusRoundIdentifier) -> Boolean = mock()
    whenever(shouldBuildNextBlockPredicate.invoke(eq(expectedConsensusRoundIdentifier))).thenReturn(true)
    val nextBlockTimestampProvider: (ConsensusRoundIdentifier) -> Long = mock()
    whenever(nextBlockTimestampProvider.invoke(eq(expectedConsensusRoundIdentifier))).thenReturn(nextBlockTimestamp)
    beaconBlockImporter =
      BeaconBlockImporterImpl(
        executionLayerManager,
        { finalizationState },
        nextBlockTimestampProvider,
        shouldBuildNextBlockPredicate,
        blockBuilderIdentity,
      )

    beaconBlockImporter.importBlock(randomBeaconBlock)

    verify(shouldBuildNextBlockPredicate, times(1)).invoke(eq(expectedConsensusRoundIdentifier))
    verify(nextBlockTimestampProvider, times(1)).invoke(eq(expectedConsensusRoundIdentifier))
  }
}
