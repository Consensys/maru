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

import encodeHex
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ExecutionException
import kotlin.random.Random
import maru.core.ext.DataGenerators
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.hyperledger.besu.plugin.services.metrics.MetricCategory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        db.storeState(testBeaconState).get()
        val beaconState = db.getBeaconState(testBeaconState.latestBeaconBlockRoot).get()
        assertThat(beaconState).isEqualTo(testBeaconState)
      }
    }

    createDatabase(databasePath).use { db ->
      testBeaconStates.forEach { testBeaconState ->
        val beaconState = db.getBeaconState(testBeaconState.latestBeaconBlockRoot).get()
        assertThat(beaconState).isEqualTo(testBeaconState)
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
        db.storeState(testBeaconState).get()
        val beaconState = db.getBeaconState(testBeaconState.latestBeaconBlockRoot).get()
        assertThat(beaconState).isEqualTo(testBeaconState)

        val latestBeaconState = db.getLatestBeaconState().get()
        assertThat(latestBeaconState).isEqualTo(testBeaconState)
      }
    }

    createDatabase(databasePath).use { db ->
      val latestBeaconState = db.getLatestBeaconState().get()
      assertThat(latestBeaconState).isEqualTo(testBeaconStates.last())
    }
  }

  @Test
  fun `test invalid key read`(
    @TempDir databasePath: Path,
  ) {
    val randomKey = Random.nextBytes(32)
    createDatabase(databasePath).use { db ->
      val exception =
        assertThrows<ExecutionException> {
          db.getBeaconState(randomKey).get()
        }
      assertThat(exception.cause?.message)
        .isEqualTo(
          "Could not find beacon state for beaconBlockRoot: ${randomKey.encodeHex()}",
        )
    }
  }

  @Test
  fun `test read and write beacon blocks`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlockMap = (1..10).map { Random.nextBytes(32) to DataGenerators.randomBeaconBlock(it.toULong()) }
    createDatabase(databasePath).use { db ->
      testBeaconBlockMap.forEach { (testBeaconBlockRoot, testBeaconBlock) ->
        db.storeBeaconBlock(testBeaconBlock, testBeaconBlockRoot).get()
        val beaconBlock = db.getBeaconBlock(testBeaconBlockRoot).get()
        assertThat(beaconBlock).isEqualTo(testBeaconBlock)
      }
    }

    createDatabase(databasePath).use { db ->
      testBeaconBlockMap.forEach { (testBeaconBlockRoot, testBeaconBlock) ->
        val beaconBlock = db.getBeaconBlock(testBeaconBlockRoot).get()
        assertThat(beaconBlock).isEqualTo(testBeaconBlock)
      }
    }
  }

  @Test
  fun `test repeated write`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlockRoot = Random.nextBytes(32)
    val testBeaconBlock = DataGenerators.randomBeaconBlock(1uL)
    createDatabase(databasePath).use { db ->
      db.storeBeaconBlock(testBeaconBlock, testBeaconBlockRoot)
      db.storeBeaconBlock(testBeaconBlock, testBeaconBlockRoot)
    }

    createDatabase(databasePath).use { db ->
      val beaconBlock = db.getBeaconBlock(testBeaconBlockRoot).get()
      assertThat(beaconBlock).isEqualTo(testBeaconBlock)
    }
  }
}
