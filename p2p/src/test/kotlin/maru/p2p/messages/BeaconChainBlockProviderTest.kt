/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import maru.core.ext.DataGenerators
import maru.database.BeaconChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class BeaconChainBlockProviderTest {
  private lateinit var beaconChain: BeaconChain
  private lateinit var blockProvider: BeaconChainBlockProvider

  @BeforeEach
  fun setup() {
    beaconChain = mock()
    blockProvider = BeaconChainBlockProvider(beaconChain)
  }

  @Test
  fun `returns empty list when no blocks found`() {
    whenever(beaconChain.getSealedBeaconBlock(any<ULong>())).thenReturn(null)

    val blocks = blockProvider.getBlocksByRange(startBlockNumber = 100UL, count = 10UL)

    assertThat(blocks).isEmpty()
  }

  @Test
  fun `returns single block when only one exists`() {
    val block = DataGenerators.randomSealedBeaconBlock(number = 100UL)
    whenever(beaconChain.getSealedBeaconBlock(100UL)).thenReturn(block)
    whenever(beaconChain.getSealedBeaconBlock(101UL)).thenReturn(null)

    val blocks = blockProvider.getBlocksByRange(startBlockNumber = 100UL, count = 10UL)

    assertThat(blocks).hasSize(1)
    assertThat(blocks[0]).isEqualTo(block)
  }

  @Test
  fun `returns multiple blocks in sequence`() {
    val block100 = DataGenerators.randomSealedBeaconBlock(number = 100UL)
    val block101 = DataGenerators.randomSealedBeaconBlock(number = 101UL)
    val block102 = DataGenerators.randomSealedBeaconBlock(number = 102UL)

    whenever(beaconChain.getSealedBeaconBlock(100UL)).thenReturn(block100)
    whenever(beaconChain.getSealedBeaconBlock(101UL)).thenReturn(block101)
    whenever(beaconChain.getSealedBeaconBlock(102UL)).thenReturn(block102)
    whenever(beaconChain.getSealedBeaconBlock(103UL)).thenReturn(null)

    val blocks = blockProvider.getBlocksByRange(startBlockNumber = 100UL, count = 10UL)

    assertThat(blocks).hasSize(3)
    assertThat(blocks[0]).isEqualTo(block100)
    assertThat(blocks[1]).isEqualTo(block101)
    assertThat(blocks[2]).isEqualTo(block102)
  }

  @Test
  fun `stops at gap in block sequence`() {
    val block100 = DataGenerators.randomSealedBeaconBlock(number = 100UL)
    val block101 = DataGenerators.randomSealedBeaconBlock(number = 101UL)
    val block103 = DataGenerators.randomSealedBeaconBlock(number = 103UL)

    whenever(beaconChain.getSealedBeaconBlock(100UL)).thenReturn(block100)
    whenever(beaconChain.getSealedBeaconBlock(101UL)).thenReturn(block101)
    whenever(beaconChain.getSealedBeaconBlock(102UL)).thenReturn(null) // Gap
    whenever(beaconChain.getSealedBeaconBlock(103UL)).thenReturn(block103)

    val blocks = blockProvider.getBlocksByRange(startBlockNumber = 100UL, count = 10UL)

    assertThat(blocks).hasSize(2)
    assertThat(blocks[0]).isEqualTo(block100)
    assertThat(blocks[1]).isEqualTo(block101)
    // Should not include block103 due to gap
  }

  @Test
  fun `respects maximum block limit`() {
    // Create blocks for all requested positions
    val allBlocks =
      (0UL until 100UL).map { i ->
        DataGenerators.randomSealedBeaconBlock(number = 100UL + i)
      }

    allBlocks.forEachIndexed { index, block ->
      whenever(beaconChain.getSealedBeaconBlock(100UL + index.toULong())).thenReturn(block)
    }

    val blocks = blockProvider.getBlocksByRange(startBlockNumber = 100UL, count = 100UL)

    assertThat(blocks).hasSize(BeaconChainBlockProvider.MAX_BLOCKS_PER_REQUEST.toInt())
    assertThat(blocks.size).isEqualTo(64)
  }

  @Test
  fun `returns requested count when less than max`() {
    val requestedCount = 10UL
    val blocks =
      (0UL until requestedCount).map { i ->
        DataGenerators.randomSealedBeaconBlock(number = 100UL + i)
      }

    blocks.forEachIndexed { index, block ->
      whenever(beaconChain.getSealedBeaconBlock(100UL + index.toULong())).thenReturn(block)
    }
    whenever(beaconChain.getSealedBeaconBlock(110UL)).thenReturn(null)

    val result = blockProvider.getBlocksByRange(startBlockNumber = 100UL, count = requestedCount)

    assertThat(result).hasSize(requestedCount.toInt())
  }

  @Test
  fun `handles zero count request`() {
    val blocks = blockProvider.getBlocksByRange(startBlockNumber = 100UL, count = 0UL)

    assertThat(blocks).isEmpty()
  }

  @Test
  fun `handles request starting at block 0`() {
    val block0 = DataGenerators.randomSealedBeaconBlock(number = 0UL)
    val block1 = DataGenerators.randomSealedBeaconBlock(number = 1UL)

    whenever(beaconChain.getSealedBeaconBlock(0UL)).thenReturn(block0)
    whenever(beaconChain.getSealedBeaconBlock(1UL)).thenReturn(block1)
    whenever(beaconChain.getSealedBeaconBlock(2UL)).thenReturn(null)

    val blocks = blockProvider.getBlocksByRange(startBlockNumber = 0UL, count = 10UL)

    assertThat(blocks).hasSize(2)
    assertThat(blocks[0]).isEqualTo(block0)
    assertThat(blocks[1]).isEqualTo(block1)
  }
}
