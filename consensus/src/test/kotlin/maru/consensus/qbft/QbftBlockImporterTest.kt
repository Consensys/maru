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

import com.github.michaelbull.result.Ok
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import maru.consensus.qbft.adaptors.QbftSealedBlockAdaptor
import maru.consensus.state.StateTransition
import maru.core.BeaconState
import maru.core.SealedBeaconBlock
import maru.core.ext.DataGenerators
import maru.database.Database
import maru.database.Updater
import maru.executionlayer.manager.ExecutionLayerManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture

class QbftBlockImporterTest {
  private var executionLayerManager: ExecutionLayerManager = mock()

  private val stateTransition: StateTransition = mock()

  private lateinit var blockchain: Database
  private var beaconBlockImporterResponse =
    SafeFuture.completedFuture(DataGenerators.randomValidForkChoiceUpdatedResult())

  private lateinit var qbftBlockImporter: QbftBlockImportCoordinator
  private lateinit var initialBeaconState: BeaconState

  @BeforeEach
  fun setUp() {
    initialBeaconState = DataGenerators.randomBeaconState(2UL)
    blockchain = InMemoryDatabase(initialBeaconState)
    qbftBlockImporter =
      QbftBlockImportCoordinator(
        blockchain = blockchain,
        stateTransition = stateTransition,
        beaconBlockImporter = { beaconBlockImporterResponse },
      )
  }

  @AfterEach
  fun tearDown() {
    reset(executionLayerManager)
    reset(stateTransition)
  }

  @Test
  fun `importBlock returns true on successful import`() {
    val qbftBlock = QbftSealedBlockAdaptor(DataGenerators.randomSealedBeaconBlock(2UL))
    val beaconState = DataGenerators.randomBeaconState(2UL)

    whenever(stateTransition.processBlock(any(), any())).thenReturn(SafeFuture.completedFuture(Ok(beaconState)))
    whenever(executionLayerManager.setHead(any(), any(), any())).thenReturn(
      SafeFuture.completedFuture(
        DataGenerators
          .randomValidForkChoiceUpdatedResult(null),
      ),
    )

    val result = qbftBlockImporter.importBlock(qbftBlock)
    assertTrue(result)
    assertThat(blockchain.getLatestBeaconState()).isEqualTo(beaconState)
  }

  @Test
  fun `importBlock rolls the DB update back on state transition failure`() {
    val qbftBlock = QbftSealedBlockAdaptor(DataGenerators.randomSealedBeaconBlock(2UL))
    whenever(stateTransition.processBlock(any(), any())).thenThrow(RuntimeException("Test exception"))
    val stateBeforeTransition = blockchain.getLatestBeaconState()

    val result = qbftBlockImporter.importBlock(qbftBlock)

    assertFalse(result)
    val stateAfterTransition = blockchain.getLatestBeaconState()
    assertThat(stateBeforeTransition).isEqualTo(stateAfterTransition)
  }

  @Test
  fun `importBlock rolls the DB update back on block import`() {
    val qbftBlock = QbftSealedBlockAdaptor(DataGenerators.randomSealedBeaconBlock(2UL))
    beaconBlockImporterResponse = SafeFuture.failedFuture(RuntimeException("Test exception"))
    val stateBeforeTransition = blockchain.getLatestBeaconState()

    val result = qbftBlockImporter.importBlock(qbftBlock)

    assertFalse(result)
    val stateAfterTransition = blockchain.getLatestBeaconState()
    assertThat(stateBeforeTransition).isEqualTo(stateAfterTransition)
  }

  private class InMemoryDatabase(
    initialBeaconState: BeaconState,
  ) : Database {
    private val beaconStateByBlockRoot = mutableMapOf<ByteArray, BeaconState>()
    private val sealedBeaconBlockByBlockRoot = mutableMapOf<ByteArray, SealedBeaconBlock>()
    private var latestBeaconState: BeaconState = initialBeaconState

    override fun getLatestBeaconState(): BeaconState = latestBeaconState

    override fun findBeaconState(beaconBlockRoot: ByteArray): BeaconState? = beaconStateByBlockRoot[beaconBlockRoot]

    override fun findSealedBeaconBlock(beaconBlockRoot: ByteArray): SealedBeaconBlock? =
      sealedBeaconBlockByBlockRoot[beaconBlockRoot]

    override fun newUpdater(): Updater = InMemoryUpdater(this)

    override fun close() {
      // No-op for in-memory database
    }

    private class InMemoryUpdater(
      private val database: InMemoryDatabase,
    ) : Updater {
      private val beaconStateByBlockRoot = mutableMapOf<ByteArray, BeaconState>()
      private val sealedBeaconBlockByBlockRoot = mutableMapOf<ByteArray, SealedBeaconBlock>()
      private var newBeaconState: BeaconState? = null

      override fun putBeaconState(beaconState: BeaconState): Updater {
        beaconStateByBlockRoot[beaconState.latestBeaconBlockRoot] = beaconState
        newBeaconState = beaconState
        return this
      }

      override fun putSealedBeaconBlock(
        sealedBeaconBlock: SealedBeaconBlock,
        beaconBlockRoot: ByteArray,
      ): Updater {
        sealedBeaconBlockByBlockRoot[beaconBlockRoot] = sealedBeaconBlock
        return this
      }

      override fun commit() {
        database.beaconStateByBlockRoot.putAll(beaconStateByBlockRoot)
        database.sealedBeaconBlockByBlockRoot.putAll(sealedBeaconBlockByBlockRoot)
        if (newBeaconState != null) {
          database.latestBeaconState = newBeaconState!!
        }
      }

      override fun rollback() {
        beaconStateByBlockRoot.clear()
        sealedBeaconBlockByBlockRoot.clear()
        newBeaconState = null
      }

      override fun close() {
        // No-op for in-memory updater
      }
    }
  }
}
