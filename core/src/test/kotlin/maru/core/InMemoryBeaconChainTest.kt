/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.core

import maru.core.ext.DataGenerators
import maru.database.BeaconChain
import maru.database.BeaconChainTestSuite
import maru.database.InMemoryBeaconChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryBeaconChainTest : BeaconChainTestSuite() {
  private lateinit var initialBeaconState: BeaconState
  private lateinit var inMemoryBeaconChain: InMemoryBeaconChain

  override fun createBeaconChain(initialBeaconState: BeaconState): BeaconChain {
    val chain = InMemoryBeaconChain(initialBeaconState)
    return chain
  }

  @BeforeEach
  fun setUp() {
    initialBeaconState = DataGenerators.randomBeaconState(2UL)
    inMemoryBeaconChain = InMemoryBeaconChain(initialBeaconState)
  }

  @Test
  fun `initial state can be found by number`() {
    val initialBeaconStateByNumber =
      inMemoryBeaconChain.getBeaconState(initialBeaconState.beaconBlockHeader.number)
    assertThat(initialBeaconStateByNumber).isEqualTo(initialBeaconState)
  }

  @Test
  fun `uncommited changes are not visible by InMemoryBeaconChain`() {
    val newBeaconState = DataGenerators.randomBeaconState(6UL)
    val newBeaconBlock = DataGenerators.randomSealedBeaconBlock(7UL)
    val inflightBeaconBlockRoot = newBeaconBlock.beaconBlock.beaconBlockHeader.hash
    val updater = inMemoryBeaconChain.newBeaconChainUpdater()
    updater.putBeaconState(newBeaconState)
    updater.putSealedBeaconBlock(newBeaconBlock)

    val latestBeaconState = inMemoryBeaconChain.getLatestBeaconState()
    assertThat(latestBeaconState).isEqualTo(initialBeaconState)

    val retrievedBeaconState = inMemoryBeaconChain.getBeaconState(newBeaconState.beaconBlockHeader.hash)
    assertThat(retrievedBeaconState).isNull()

    val retrievedSealedBeaconBlockByBlockRoot = inMemoryBeaconChain.getSealedBeaconBlock(inflightBeaconBlockRoot)
    assertThat(retrievedSealedBeaconBlockByBlockRoot).isNull()

    val retrievedSealedBeaconBlockByBlockNumber =
      inMemoryBeaconChain
        .getSealedBeaconBlock(newBeaconBlock.beaconBlock.beaconBlockHeader.number)
    assertThat(retrievedSealedBeaconBlockByBlockNumber).isNull()
  }

  @Test
  fun `getBeaconState finds state by block root with different byte arrays`() {
    val newBeaconState = DataGenerators.randomBeaconState(3UL)
    val updater = inMemoryBeaconChain.newBeaconChainUpdater()
    updater.putBeaconState(newBeaconState).commit()
    val blockRootCopy = newBeaconState.beaconBlockHeader.hash.copyOf() // new instance, same content
    val found = inMemoryBeaconChain.getBeaconState(blockRootCopy)
    assertThat(found).isEqualTo(newBeaconState)
  }

  @Test
  fun `getSealedBeaconBlock finds block by block root with different byte arrays`() {
    val newBlock = DataGenerators.randomSealedBeaconBlock(4UL)
    val updater = inMemoryBeaconChain.newBeaconChainUpdater()
    updater.putSealedBeaconBlock(newBlock).commit()
    val blockRootCopy =
      newBlock.beaconBlock.beaconBlockHeader.hash
        .copyOf() // new instance, same content
    val found = inMemoryBeaconChain.getSealedBeaconBlock(blockRootCopy)
    assertThat(found).isEqualTo(newBlock)
  }
}
