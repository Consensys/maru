/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.database

import kotlin.random.Random
import maru.core.BeaconState
import maru.core.ext.DataGenerators
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Shared test suite for [BeaconChain] implementations.
 *
 * Subclasses must implement [createBeaconChain] to provide a fresh, initialized instance
 * for each test. The instance should already contain an initial beacon state so that
 * [BeaconChain.getLatestBeaconState] returns a valid result.
 */
abstract class BeaconChainTestSuite {
  /**
   * Creates a new [BeaconChain] instance pre-loaded with [initialBeaconState].
   * Called once per test method.
   */
  abstract fun createBeaconChain(initialBeaconState: BeaconState): BeaconChain

  private fun withBeaconChain(
    initialBlockNumber: ULong = 2UL,
    block: (BeaconChain, BeaconState) -> Unit,
  ) {
    val initialBeaconState = DataGenerators.randomBeaconState(initialBlockNumber)
    val beaconChain = createBeaconChain(initialBeaconState)
    beaconChain.use { block(it, initialBeaconState) }
  }

  @Test
  fun `getLatestBeaconState returns initial state`() = withBeaconChain { db, initialState ->
    assertThat(db.getLatestBeaconState()).isEqualTo(initialState)
  }

  @Test
  fun `getBeaconState returns null for unknown block root`() = withBeaconChain { db, _ ->
    assertThat(db.getBeaconState(Random.nextBytes(32))).isNull()
  }

  @Test
  fun `getSealedBeaconBlock returns null for unknown block root`() = withBeaconChain { db, _ ->
    assertThat(db.getSealedBeaconBlock(Random.nextBytes(32))).isNull()
  }

  @Test
  fun `getSealedBeaconBlock returns null for unknown block number`() = withBeaconChain { db, _ ->
    assertThat(db.getSealedBeaconBlock(100uL)).isNull()
  }

  @Test
  fun `newBeaconChainUpdater can put and commit beacon state`() = withBeaconChain { db, _ ->
    val newBeaconState = DataGenerators.randomBeaconState(3UL)
    db.newBeaconChainUpdater().use {
      it.putBeaconState(newBeaconState).commit()
    }

    assertThat(db.getLatestBeaconState()).isEqualTo(newBeaconState)
    assertThat(db.getBeaconState(newBeaconState.beaconBlockHeader.hash)).isEqualTo(newBeaconState)
  }

  @Test
  fun `newBeaconChainUpdater can put and commit sealed beacon block`() = withBeaconChain { db, _ ->
    val sealedBeaconBlock = DataGenerators.randomSealedBeaconBlock(3UL)
    val beaconBlockRoot = sealedBeaconBlock.beaconBlock.beaconBlockHeader.hash
    db.newBeaconChainUpdater().use {
      it.putSealedBeaconBlock(sealedBeaconBlock).commit()
    }

    assertThat(db.getSealedBeaconBlock(beaconBlockRoot)).isEqualTo(sealedBeaconBlock)
    assertThat(
      db.getSealedBeaconBlock(sealedBeaconBlock.beaconBlock.beaconBlockHeader.number),
    ).isEqualTo(sealedBeaconBlock)
  }

  @Test
  fun `newBeaconChainUpdater can rollback changes`() = withBeaconChain { db, initialState ->
    val newBeaconState = DataGenerators.randomBeaconState(4UL)
    val sealedBeaconBlock = DataGenerators.randomSealedBeaconBlock(5UL)
    val beaconBlockRoot = sealedBeaconBlock.beaconBlock.beaconBlockHeader.hash
    db.newBeaconChainUpdater().use {
      it.putBeaconState(newBeaconState)
      it.putSealedBeaconBlock(sealedBeaconBlock)
      it.rollback()
    }

    assertThat(db.getLatestBeaconState()).isEqualTo(initialState)
    assertThat(db.getBeaconState(newBeaconState.beaconBlockHeader.hash)).isNull()
    assertThat(db.getSealedBeaconBlock(beaconBlockRoot)).isNull()
    assertThat(
      db.getSealedBeaconBlock(sealedBeaconBlock.beaconBlock.beaconBlockHeader.number),
    ).isNull()
  }

  @Test
  fun `initial state can be found by hash`() = withBeaconChain { db, initialState ->
    assertThat(db.getBeaconState(initialState.beaconBlockHeader.hash)).isEqualTo(initialState)
  }

  @Test
  fun `getSealedBeaconBlocks returns consecutive blocks`() = withBeaconChain { db, _ ->
    val testBlocks = (0uL..5uL).map { DataGenerators.randomSealedBeaconBlock(it) }

    db.newBeaconChainUpdater().use { updater ->
      testBlocks.forEach { block -> updater.putSealedBeaconBlock(block) }
      updater.putBeaconState(
        BeaconState(
          beaconBlockHeader = testBlocks.last().beaconBlock.beaconBlockHeader,
          validators = DataGenerators.randomValidators(),
        ),
      )
      updater.commit()
    }

    val startBlockNumber = 2uL
    val count = 3uL
    val blocks = db.getSealedBeaconBlocks(startBlockNumber, count)
    assertThat(blocks).hasSize(3)
    assertThat(blocks).isEqualTo(testBlocks.subList(startBlockNumber.toInt(), (startBlockNumber + count).toInt()))
  }

  @Test
  fun `getSealedBeaconBlocks returns empty list when count is zero`() = withBeaconChain { db, _ ->
    val testBlock = DataGenerators.randomSealedBeaconBlock(1uL)
    db.newBeaconChainUpdater().use { updater ->
      updater.putSealedBeaconBlock(testBlock)
      updater.putBeaconState(
        BeaconState(
          beaconBlockHeader = testBlock.beaconBlock.beaconBlockHeader,
          validators = DataGenerators.randomValidators(),
        ),
      )
      updater.commit()
    }

    assertThat(db.getSealedBeaconBlocks(startBlockNumber = 1uL, count = 0uL)).isEmpty()
  }

  @Test
  fun `getSealedBeaconBlocks stops at gap in sequence`() = withBeaconChain { db, _ ->
    val block1 = DataGenerators.randomSealedBeaconBlock(1uL)
    val block2 = DataGenerators.randomSealedBeaconBlock(2uL)
    val block4 = DataGenerators.randomSealedBeaconBlock(4uL)

    db.newBeaconChainUpdater().use { updater ->
      updater
        .putSealedBeaconBlock(block1)
        .putSealedBeaconBlock(block2)
        .putSealedBeaconBlock(block4)
        .putBeaconState(
          BeaconState(
            beaconBlockHeader = block4.beaconBlock.beaconBlockHeader,
            validators = DataGenerators.randomValidators(),
          ),
        ).commit()
    }

    assertThatThrownBy {
      db.getSealedBeaconBlocks(startBlockNumber = 1uL, count = 5uL)
    }.isInstanceOf(IllegalStateException::class.java)
      .hasMessage("Missing sealed beacon block 3")
  }

  @Test
  fun `getSealedBeaconBlocks returns available blocks when count exceeds available`() = withBeaconChain { db, _ ->
    val testBlocks = (1uL..3uL).map { DataGenerators.randomSealedBeaconBlock(it) }

    db.newBeaconChainUpdater().use { updater ->
      testBlocks.forEach { block -> updater.putSealedBeaconBlock(block) }
      updater.putBeaconState(
        BeaconState(
          beaconBlockHeader = testBlocks.last().beaconBlock.beaconBlockHeader,
          validators = DataGenerators.randomValidators(),
        ),
      )
      updater.commit()
    }

    val blocks = db.getSealedBeaconBlocks(startBlockNumber = 1uL, count = 10uL)
    assertThat(blocks).hasSize(3)
    assertThat(blocks).isEqualTo(testBlocks)
  }
}
