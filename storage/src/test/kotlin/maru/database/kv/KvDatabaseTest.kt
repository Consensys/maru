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
import java.util.Optional
import kotlin.random.Random
import maru.core.BeaconState
import maru.core.ext.DataGenerators
import maru.database.BeaconChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.hyperledger.besu.plugin.services.metrics.MetricCategory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KvDatabaseTest {
  private object KvDatabaseTestMetricCategory : MetricCategory {
    override fun getName(): String = KvDatabaseTest::class.simpleName!!

    override fun getApplicationPrefix(): Optional<String> = Optional.empty()
  }

  private fun createDatabase(databasePath: Path): BeaconChain =
    KvDatabaseFactory.createRocksDbDatabase(
      databasePath = databasePath,
      metricsSystem = NoOpMetricsSystem(),
      metricCategory = KvDatabaseTestMetricCategory,
    )

  @Test
  fun `test read and write beacon state`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconStates = (1..10).map { DataGenerators.randomBeaconState(it.toULong()) }
    createDatabase(databasePath).use { db ->
      testBeaconStates.forEach { testBeaconState ->
        db.newUpdater().use {
          it.putBeaconState(testBeaconState).commit()
        }
        assertThat(db.getBeaconState(testBeaconState.latestBeaconBlockHeader.hash))
          .isEqualTo(testBeaconState)
      }
    }

    createDatabase(databasePath).use { db ->
      testBeaconStates.forEach { testBeaconState ->
        assertThat(db.getBeaconState(testBeaconState.latestBeaconBlockHeader.hash))
          .isEqualTo(testBeaconState)
      }
    }
  }

  @Test
  fun `test read and write latest beacon state`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconStates = (1..10).map { DataGenerators.randomBeaconState(it.toULong()) }
    createDatabase(databasePath).use { db ->
      testBeaconStates.forEach { testBeaconState ->
        db.newUpdater().use {
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
  fun `test invalid key read`(
    @TempDir databasePath: Path,
  ) {
    val randomKey = Random.nextBytes(32)
    createDatabase(databasePath).use { db ->
      assertThat(db.getBeaconState(randomKey)).isNull()
      assertThat(db.getSealedBeaconBlock(randomKey)).isNull()
      assertThat(db.getSealedBeaconBlock(100uL)).isNull()
    }
  }

  @Test
  fun `test read and write beacon blocks`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlocks =
      (1..10).map { DataGenerators.randomSealedBeaconBlock(it.toULong()) }
    createDatabase(databasePath).use { db ->
      testBeaconBlocks.forEach { testBeaconBlock ->
        db.newUpdater().use {
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
  fun `test repeated write`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlock = DataGenerators.randomSealedBeaconBlock(1uL)
    createDatabase(databasePath).use { db ->
      db.newUpdater().use {
        it.putSealedBeaconBlock(testBeaconBlock).commit()
      }
      db.newUpdater().use {
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
  fun `test update rollback`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlock1 = DataGenerators.randomSealedBeaconBlock(1uL)
    val testBeaconBlock1Number = testBeaconBlock1.beaconBlock.beaconBlockHeader.number
    val testBeaconBlock1Root = testBeaconBlock1.beaconBlock.beaconBlockHeader.hash
    val testBeaconBlock2 = DataGenerators.randomSealedBeaconBlock(2uL)
    val testBeaconBlock2Number = testBeaconBlock2.beaconBlock.beaconBlockHeader.number
    val testBeaconBlock2Root = testBeaconBlock2.beaconBlock.beaconBlockHeader.hash
    createDatabase(databasePath).use { db ->
      db.newUpdater().use {
        it.putSealedBeaconBlock(testBeaconBlock1).commit()
      }
      assertThat(db.getSealedBeaconBlock(testBeaconBlock1Root)).isEqualTo(testBeaconBlock1)
      assertThat(db.getSealedBeaconBlock(testBeaconBlock1Number)).isEqualTo(testBeaconBlock1)

      assertThat(db.getSealedBeaconBlock(testBeaconBlock2Root)).isNull()
      assertThat(db.getSealedBeaconBlock(testBeaconBlock2Number)).isNull()

      db.newUpdater().use { it.putSealedBeaconBlock(testBeaconBlock2).rollback() }

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
  fun `test getSealedBeaconBlocks returns consecutive blocks`(
    @TempDir databasePath: Path,
  ) {
    val testBlocks = (0uL..5uL).map { DataGenerators.randomSealedBeaconBlock(it) }

    createDatabase(databasePath).use { db ->
      // Store blocks
      testBlocks.forEach { block ->
        db.newUpdater().use {
          it.putSealedBeaconBlock(block).commit()
        }
      }
      db.newUpdater().use {
        it
          .putBeaconState(
            BeaconState(
              latestBeaconBlockHeader = testBlocks.last().beaconBlock.beaconBlockHeader,
              validators = DataGenerators.randomValidators(),
            ),
          ).commit()
      }

      // Get blocks 2-4
      val startBlockNumber = 2uL
      val count = 3uL
      val blocks = db.getSealedBeaconBlocks(startBlockNumber, count)
      assertThat(blocks).hasSize(3)
      assertThat(blocks).isEqualTo(testBlocks.subList(startBlockNumber.toInt(), (startBlockNumber + count).toInt()))
    }
  }

  @Test
  fun `test getSealedBeaconBlocks returns empty list when count is zero`(
    @TempDir databasePath: Path,
  ) {
    val testBlock = DataGenerators.randomSealedBeaconBlock(1uL)

    createDatabase(databasePath).use { db ->
      db.newUpdater().use {
        it.putSealedBeaconBlock(testBlock).commit()
      }
      db.newUpdater().use {
        it
          .putBeaconState(
            BeaconState(
              latestBeaconBlockHeader = testBlock.beaconBlock.beaconBlockHeader,
              validators = DataGenerators.randomValidators(),
            ),
          ).commit()
      }

      val blocks = db.getSealedBeaconBlocks(startBlockNumber = 1uL, count = 0uL)
      assertThat(blocks).isEmpty()
    }
  }

  @Test
  fun `test getSealedBeaconBlocks stops at gap in sequence`(
    @TempDir databasePath: Path,
  ) {
    val block1 = DataGenerators.randomSealedBeaconBlock(1uL)
    val block2 = DataGenerators.randomSealedBeaconBlock(2uL)
    // Skip block 3
    val block4 = DataGenerators.randomSealedBeaconBlock(4uL)

    createDatabase(databasePath).use { db ->
      db.newUpdater().use {
        it
          .putSealedBeaconBlock(block1)
          .putSealedBeaconBlock(block2)
          .putSealedBeaconBlock(block4)
          .commit()
      }
      db.newUpdater().use {
        it
          .putBeaconState(
            BeaconState(
              latestBeaconBlockHeader = block4.beaconBlock.beaconBlockHeader,
              validators = DataGenerators.randomValidators(),
            ),
          ).commit()
      }

      // Request 5 blocks starting from 1, should throw exception at gap
      assertThatThrownBy {
        db.getSealedBeaconBlocks(startBlockNumber = 1uL, count = 5uL)
      }.isInstanceOf(IllegalStateException::class.java)
        .hasMessage("Missing sealed beacon block 3")
    }
  }

  @Test
  fun `test getSealedBeaconBlocks returns available blocks when count exceeds available`(
    @TempDir databasePath: Path,
  ) {
    val testBlocks = (1uL..3uL).map { DataGenerators.randomSealedBeaconBlock(it) }

    createDatabase(databasePath).use { db ->
      testBlocks.forEach { block ->
        db.newUpdater().use {
          it.putSealedBeaconBlock(block).commit()
        }
      }
      db.newUpdater().use {
        it
          .putBeaconState(
            BeaconState(
              latestBeaconBlockHeader = testBlocks.last().beaconBlock.beaconBlockHeader,
              validators = DataGenerators.randomValidators(),
            ),
          ).commit()
      }

      // Request 10 blocks but only 3 exist
      val blocks = db.getSealedBeaconBlocks(startBlockNumber = 1uL, count = 10uL)
      assertThat(blocks).hasSize(3)
      assertThat(blocks).isEqualTo(testBlocks)
    }
  }
}
