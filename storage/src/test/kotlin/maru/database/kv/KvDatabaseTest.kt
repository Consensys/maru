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
package maru.database.kv

import java.nio.file.Path
import java.util.Optional
import kotlin.random.Random
import maru.core.ext.DataGenerators
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.hyperledger.besu.plugin.services.metrics.MetricCategory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KvDatabaseTest {
  private object KvDatabaseTestMetricCategory : MetricCategory {
    override fun getName(): String = KvDatabaseTest::class.simpleName!!

    override fun getApplicationPrefix(): Optional<String> = Optional.empty()
  }

  private fun createDatabase(databasePath: Path): KvDatabase =
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
        assertThat(db.findBeaconState(testBeaconState.latestBeaconBlockRoot))
          .isEqualTo(testBeaconState)
      }
    }

    createDatabase(databasePath).use { db ->
      testBeaconStates.forEach { testBeaconState ->
        assertThat(db.findBeaconState(testBeaconState.latestBeaconBlockRoot))
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
      assertThat(db.findBeaconState(randomKey)).isNull()
      assertThat(db.findSealedBeaconBlock(randomKey)).isNull()
    }
  }

  @Test
  fun `test read and write beacon blocks`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlockMap =
      (1..10).map {
        Random.nextBytes(
          32,
        ) to DataGenerators.randomSealedBeaconBlock(it.toULong())
      }
    createDatabase(databasePath).use { db ->
      testBeaconBlockMap.forEach { (testBeaconBlockRoot, testBeaconBlock) ->
        db.newUpdater().use {
          it.putSealedBeaconBlock(testBeaconBlock, testBeaconBlockRoot).commit()
        }
        assertThat(db.findSealedBeaconBlock(testBeaconBlockRoot)).isEqualTo(testBeaconBlock)
      }
    }

    createDatabase(databasePath).use { db ->
      testBeaconBlockMap.forEach { (testBeaconBlockRoot, testBeaconBlock) ->
        assertThat(db.findSealedBeaconBlock(testBeaconBlockRoot)).isEqualTo(testBeaconBlock)
      }
    }
  }

  @Test
  fun `test repeated write`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlock = DataGenerators.randomSealedBeaconBlock(1uL)
    val testBeaconBlockRoot = Random.nextBytes(32)
    createDatabase(databasePath).use { db ->
      db.newUpdater().use {
        it.putSealedBeaconBlock(testBeaconBlock, testBeaconBlockRoot).commit()
      }
      db.newUpdater().use {
        it.putSealedBeaconBlock(testBeaconBlock, testBeaconBlockRoot).commit()
      }
    }

    createDatabase(databasePath).use { db ->
      assertThat(db.findSealedBeaconBlock(testBeaconBlockRoot)).isEqualTo(testBeaconBlock)
    }
  }

  @Test
  fun `test update rollback`(
    @TempDir databasePath: Path,
  ) {
    val testBeaconBlock1 = DataGenerators.randomSealedBeaconBlock(1uL)
    val testBeaconBlockRoot1 = Random.nextBytes(32)
    val testBeaconBlock2 = DataGenerators.randomSealedBeaconBlock(2uL)
    val testBeaconBlockRoot2 = Random.nextBytes(32)
    createDatabase(databasePath).use { db ->
      db.newUpdater().use {
        it.putSealedBeaconBlock(testBeaconBlock1, testBeaconBlockRoot1).commit()
      }
      assertThat(db.findSealedBeaconBlock(testBeaconBlockRoot1)).isEqualTo(testBeaconBlock1)

      assertThat(db.findSealedBeaconBlock(testBeaconBlockRoot2)).isNull()

      db.newUpdater().use { it.putSealedBeaconBlock(testBeaconBlock2, testBeaconBlockRoot2).rollback() }

      assertThat(db.findSealedBeaconBlock(testBeaconBlockRoot1)).isEqualTo(testBeaconBlock1)

      assertThat(db.findSealedBeaconBlock(testBeaconBlockRoot2)).isNull()
    }

    createDatabase(databasePath).use { db ->
      assertThat(db.findSealedBeaconBlock(testBeaconBlockRoot1)).isEqualTo(testBeaconBlock1)
      assertThat(db.findSealedBeaconBlock(testBeaconBlockRoot2)).isNull()
    }
  }
}
