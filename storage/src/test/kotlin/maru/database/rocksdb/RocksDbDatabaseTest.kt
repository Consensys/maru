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
package maru.database.rocksdb

import java.nio.file.Path
import java.util.Optional
import kotlin.random.Random
import maru.core.ext.DataGenerators
import maru.serialization.rlp.getRoot
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.hyperledger.besu.plugin.services.metrics.MetricCategory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tech.pegasys.teku.storage.server.kvstore.KvStoreConfiguration
import tech.pegasys.teku.storage.server.rocksdb.RocksDbInstanceFactory

class RocksDbDatabaseTest {
  private object RocksDbDatabaseTestMetricCategory : MetricCategory {
    override fun getName(): String = RocksDbDatabaseTest::class.simpleName!!

    override fun getApplicationPrefix(): Optional<String> = Optional.empty()
  }

  private fun createDatabase(databasePath: Path): RocksDbDatabase {
    val rocksDbInstance =
      RocksDbInstanceFactory.create(
        NoOpMetricsSystem(),
        RocksDbDatabaseTestMetricCategory,
        KvStoreConfiguration().withDatabaseDir(databasePath),
        listOf(
          RocksDbDatabase.Companion.Schema.BeaconBlockByBlockRoot,
          RocksDbDatabase.Companion.Schema.BeaconStateByBlockRoot,
        ),
        emptyList(),
      )
    return RocksDbDatabase(rocksDbInstance)
  }

  @Test
  fun `test read and write beacon state`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconStates = (1..10).map { DataGenerators.randomBeaconState(it.toULong()) }
    createDatabase(databasePath).use { db ->
      testBeaconStates.forEach { testBeaconState ->
        db.newUpdater().use {
          it.setBeaconState(testBeaconState).commit()
        }
        assertThat(db.getBeaconState(testBeaconState.latestBeaconBlockRoot))
          .isPresent()
          .get()
          .isEqualTo(testBeaconState)
      }
    }

    createDatabase(databasePath).use { db ->
      testBeaconStates.forEach { testBeaconState ->
        assertThat(db.getBeaconState(testBeaconState.latestBeaconBlockRoot))
          .isPresent()
          .get()
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
          it.setBeaconState(testBeaconState).commit()
        }
        assertThat(db.getBeaconState(testBeaconState.latestBeaconBlockRoot))
          .isPresent()
          .get()
          .isEqualTo(testBeaconState)

        assertThat(db.getLatestBeaconState())
          .isPresent()
          .get()
          .isEqualTo(testBeaconState)
      }
    }

    createDatabase(databasePath).use { db ->
      assertThat(db.getLatestBeaconState())
        .isPresent()
        .get()
        .isEqualTo(testBeaconStates.last())
    }
  }

  @Test
  fun `test invalid key read`(
    @TempDir databasePath: Path,
  ) {
    val randomKey = Random.nextBytes(32)
    createDatabase(databasePath).use { db ->
      assertThat(db.getBeaconState(randomKey)).isEmpty()
      assertThat(db.getBeaconBlock(randomKey)).isEmpty()
    }
  }

  @Test
  fun `test read and write beacon blocks`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlocks = (1..10).map { DataGenerators.randomBeaconBlock(it.toULong()) }
    createDatabase(databasePath).use { db ->
      testBeaconBlocks.forEach { testBeaconBlock ->
        db.newUpdater().use {
          it.setBeaconBlock(testBeaconBlock).commit()
        }
        assertThat(db.getBeaconBlock(testBeaconBlock.getRoot()))
          .isPresent()
          .get()
          .isEqualTo(testBeaconBlock)
      }
    }

    createDatabase(databasePath).use { db ->
      testBeaconBlocks.forEach { testBeaconBlock ->
        assertThat(db.getBeaconBlock(testBeaconBlock.getRoot()))
          .isPresent()
          .get()
          .isEqualTo(testBeaconBlock)
      }
    }
  }

  @Test
  fun `test repeated write`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlock = DataGenerators.randomBeaconBlock(1uL)
    createDatabase(databasePath).use { db ->
      db.newUpdater().use {
        it.setBeaconBlock(testBeaconBlock).commit()
      }
      db.newUpdater().use {
        it.setBeaconBlock(testBeaconBlock).commit()
      }
    }

    createDatabase(databasePath).use { db ->
      assertThat(db.getBeaconBlock(testBeaconBlock.getRoot()))
        .isPresent()
        .get()
        .isEqualTo(testBeaconBlock)
    }
  }

  @Test
  fun `test update rollback`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlock1 = DataGenerators.randomBeaconBlock(1uL)
    val testBeaconBlock2 = DataGenerators.randomBeaconBlock(2uL)
    createDatabase(databasePath).use { db ->
      db.newUpdater().use {
        it.setBeaconBlock(testBeaconBlock1).commit()
      }
      assertThat(db.getBeaconBlock(testBeaconBlock1.getRoot()))
        .isPresent()
        .get()
        .isEqualTo(testBeaconBlock1)

      assertThat(db.getBeaconBlock(testBeaconBlock2.getRoot()))
        .isEmpty()

      db.newUpdater().use { it.setBeaconBlock(testBeaconBlock2).rollback() }

      assertThat(db.getBeaconBlock(testBeaconBlock1.getRoot()))
        .isPresent()
        .get()
        .isEqualTo(testBeaconBlock1)

      assertThat(db.getBeaconBlock(testBeaconBlock2.getRoot()))
        .isEmpty()
    }

    createDatabase(databasePath).use { db ->
      assertThat(db.getBeaconBlock(testBeaconBlock1.getRoot()))
        .isPresent()
        .get()
        .isEqualTo(testBeaconBlock1)

      assertThat(db.getBeaconBlock(testBeaconBlock2.getRoot()))
        .isEmpty()
    }
  }
}
