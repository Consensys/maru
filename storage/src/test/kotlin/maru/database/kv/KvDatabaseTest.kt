/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.database.kv

import java.nio.file.Path
import maru.core.BeaconState
import maru.core.ext.DataGenerators
import maru.core.ext.metrics.TestMetrics
import maru.database.BeaconChain
import maru.database.BeaconChainTestSuite
import maru.metrics.BesuMetricsCategoryAdapter
import maru.metrics.MaruMetricsCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KvDatabaseTest : BeaconChainTestSuite() {
  @field:TempDir
  lateinit var sharedDatabasePath: Path

  private fun createDatabase(databasePath: Path): KvDatabase =
    KvDatabaseFactory.createRocksDbDatabase(
      databasePath = databasePath,
      metricsSystem = TestMetrics.TestMetricsSystemAdapter,
      metricCategory = BesuMetricsCategoryAdapter.from(MaruMetricsCategory.STORAGE),
    )

  override fun createBeaconChain(initialBeaconState: BeaconState): BeaconChain {
    val db = createDatabase(sharedDatabasePath)
    db.newBeaconChainUpdater().use {
      it.putBeaconState(initialBeaconState).commit()
    }
    return db
  }

  @Test
  fun `test beacon state persists across database restarts`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconStates = (1..10).map { DataGenerators.randomBeaconState(it.toULong()) }
    createDatabase(databasePath).use { db ->
      testBeaconStates.forEach { testBeaconState ->
        db.newBeaconChainUpdater().use {
          it.putBeaconState(testBeaconState).commit()
        }
        assertThat(db.getBeaconState(testBeaconState.beaconBlockHeader.hash))
          .isEqualTo(testBeaconState)
      }
    }

    createDatabase(databasePath).use { db ->
      testBeaconStates.forEach { testBeaconState ->
        assertThat(db.getBeaconState(testBeaconState.beaconBlockHeader.hash))
          .isEqualTo(testBeaconState)
      }
    }
  }

  @Test
  fun `test latest beacon state persists across database restarts`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconStates = (1..10).map { DataGenerators.randomBeaconState(it.toULong()) }
    createDatabase(databasePath).use { db ->
      testBeaconStates.forEach { testBeaconState ->
        db.newBeaconChainUpdater().use {
          it.putBeaconState(testBeaconState).commit()
        }
        assertThat(db.getLatestBeaconState())
          .isEqualTo(testBeaconState)
      }
    }

    createDatabase(databasePath).use { db ->
      assertThat(db.getLatestBeaconState())
        .isEqualTo(testBeaconStates.last())
    }
  }

  @Test
  fun `test beacon blocks persist across database restarts`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlocks =
      (1..10).map { DataGenerators.randomSealedBeaconBlock(it.toULong()) }
    createDatabase(databasePath).use { db ->
      testBeaconBlocks.forEach { testBeaconBlock ->
        db.newBeaconChainUpdater().use {
          it.putSealedBeaconBlock(testBeaconBlock).commit()
        }
        assertThat(db.getSealedBeaconBlock(testBeaconBlock.beaconBlock.beaconBlockHeader.hash))
          .isEqualTo(testBeaconBlock)

        assertThat(db.getSealedBeaconBlock(testBeaconBlock.beaconBlock.beaconBlockHeader.number))
          .isEqualTo(testBeaconBlock)
      }
    }

    createDatabase(databasePath).use { db ->
      testBeaconBlocks.forEach { testBeaconBlock ->
        assertThat(db.getSealedBeaconBlock(testBeaconBlock.beaconBlock.beaconBlockHeader.hash))
          .isEqualTo(testBeaconBlock)

        assertThat(db.getSealedBeaconBlock(testBeaconBlock.beaconBlock.beaconBlockHeader.number))
          .isEqualTo(testBeaconBlock)
      }
    }
  }

  @Test
  fun `test repeated write persists correctly`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlock = DataGenerators.randomSealedBeaconBlock(1uL)
    createDatabase(databasePath).use { db ->
      db.newBeaconChainUpdater().use {
        it.putSealedBeaconBlock(testBeaconBlock).commit()
      }
      db.newBeaconChainUpdater().use {
        it.putSealedBeaconBlock(testBeaconBlock).commit()
      }
    }

    createDatabase(databasePath).use { db ->
      assertThat(db.getSealedBeaconBlock(testBeaconBlock.beaconBlock.beaconBlockHeader.hash))
        .isEqualTo(testBeaconBlock)

      assertThat(db.getSealedBeaconBlock(testBeaconBlock.beaconBlock.beaconBlockHeader.number))
        .isEqualTo(testBeaconBlock)
    }
  }

  @Test
  fun `test rollback persists correctly across database restarts`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlock1 = DataGenerators.randomSealedBeaconBlock(1uL)
    val testBeaconBlock1Number = testBeaconBlock1.beaconBlock.beaconBlockHeader.number
    val testBeaconBlock1Root = testBeaconBlock1.beaconBlock.beaconBlockHeader.hash
    val testBeaconBlock2 = DataGenerators.randomSealedBeaconBlock(2uL)
    val testBeaconBlock2Number = testBeaconBlock2.beaconBlock.beaconBlockHeader.number
    val testBeaconBlock2Root = testBeaconBlock2.beaconBlock.beaconBlockHeader.hash
    createDatabase(databasePath).use { db ->
      db.newBeaconChainUpdater().use {
        it.putSealedBeaconBlock(testBeaconBlock1).commit()
      }
      assertThat(db.getSealedBeaconBlock(testBeaconBlock1Root)).isEqualTo(testBeaconBlock1)
      assertThat(db.getSealedBeaconBlock(testBeaconBlock1Number)).isEqualTo(testBeaconBlock1)

      assertThat(db.getSealedBeaconBlock(testBeaconBlock2Root)).isNull()
      assertThat(db.getSealedBeaconBlock(testBeaconBlock2Number)).isNull()

      db.newBeaconChainUpdater().use { it.putSealedBeaconBlock(testBeaconBlock2).rollback() }

      assertThat(db.getSealedBeaconBlock(testBeaconBlock1Root)).isEqualTo(testBeaconBlock1)
      assertThat(db.getSealedBeaconBlock(testBeaconBlock1Number)).isEqualTo(testBeaconBlock1)

      assertThat(db.getSealedBeaconBlock(testBeaconBlock2Root)).isNull()
      assertThat(db.getSealedBeaconBlock(testBeaconBlock2Number)).isNull()
    }

    createDatabase(databasePath).use { db ->
      assertThat(db.getSealedBeaconBlock(testBeaconBlock1Root)).isEqualTo(testBeaconBlock1)
      assertThat(db.getSealedBeaconBlock(testBeaconBlock1Number)).isEqualTo(testBeaconBlock1)

      assertThat(db.getSealedBeaconBlock(testBeaconBlock2Root)).isNull()
      assertThat(db.getSealedBeaconBlock(testBeaconBlock2Number)).isNull()
    }
  }

  @Test
  fun `test read and increment discovery sequence number`(
    @TempDir databasePath: Path,
  ) {
    val maxIterations = 10uL
    createDatabase(databasePath).use { db ->
      assertThat(db.getLocalNodeRecordSequenceNumber()).isEqualTo(0uL)
      (1uL..maxIterations).forEach { expectedSeqNumber ->
        db.newP2PStateUpdater().putDiscoverySequenceNumber(expectedSeqNumber).commit()
        assertThat(db.getLocalNodeRecordSequenceNumber()).isEqualTo(expectedSeqNumber)
      }
    }
    createDatabase(databasePath).use { db ->
      assertThat(db.getLocalNodeRecordSequenceNumber()).isEqualTo(maxIterations)
    }
  }
}
