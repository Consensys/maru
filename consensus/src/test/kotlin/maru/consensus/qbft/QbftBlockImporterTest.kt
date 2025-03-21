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

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlin.random.Random
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import maru.consensus.qbft.adaptors.QbftSealedBlockAdaptor
import maru.consensus.state.FinalizationState
import maru.consensus.state.StateTransition
import maru.core.BeaconState
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.database.Database
import maru.database.Updater
import maru.executionlayer.manager.BlockMetadata
import maru.executionlayer.manager.ExecutionLayerManager
import org.apache.tuweni.bytes.Bytes32
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
  private lateinit var finalizationStateProvider: () -> FinalizationState

  private lateinit var qbftBlockImporter: QbftBlockImporter
  private lateinit var initialBeaconState: BeaconState
  private val feeRecipient = Random.nextBytes(20)

  @BeforeEach
  fun setUp() {
    initialBeaconState = DataGenerators.randomBeaconState(2UL)
    blockchain = InMemoryDatabase(initialBeaconState)
    finalizationStateProvider = {
      FinalizationState(
        safeBlockHash = Bytes32.random().toArray(),
        finalizedBlockHash = Bytes32.random().toArray(),
      )
    }
    qbftBlockImporter =
      QbftBlockImporter(
        blockchain = blockchain,
        executionLayerManager = executionLayerManager,
        stateTransition = stateTransition,
        finalizationStateProvider = finalizationStateProvider,
        proposerSelector = { SafeFuture.completedFuture(Validator(feeRecipient)) },
        nextBlockTimestampProvider = { 1L },
        latestBlockMetadataProvider = { BlockMetadata(1UL, Random.nextBytes(32), 1L) },
        blockBuilderIdentity = Validator(Random.nextBytes(20)),
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
  }

  @Test
  fun `importBlock returns false on state transition error`() {
    val qbftBlock = QbftSealedBlockAdaptor(DataGenerators.randomSealedBeaconBlock(2UL))

    whenever(stateTransition.processBlock(any(), any())).thenReturn(
      SafeFuture.completedFuture(
        Err(
          StateTransition
            .StateTransitionError
            ("error"),
        ),
      ),
    )

    val result = qbftBlockImporter.importBlock(qbftBlock)

    assertFalse(result)
  }

  @Test
  fun `importBlock returns false on exception`() {
    val qbftBlock = QbftSealedBlockAdaptor(DataGenerators.randomSealedBeaconBlock(2UL))

    whenever(stateTransition.processBlock(any(), any())).thenThrow(RuntimeException("Test exception"))

    val result = qbftBlockImporter.importBlock(qbftBlock)

    assertFalse(result)
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
