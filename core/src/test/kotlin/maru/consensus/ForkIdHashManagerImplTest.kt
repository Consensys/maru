/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import maru.core.Hasher
import maru.core.ext.DataGenerators
import maru.database.InMemoryBeaconChain
import maru.serialization.Serializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

class ForkIdHashManagerImplTest {
  @Mock
  private val initialBeaconState = DataGenerators.randomBeaconState(0UL)
  private val beaconChain = InMemoryBeaconChain(initialBeaconState)

  @Mock
  private lateinit var forksSchedule: ForksSchedule

  @Mock
  private lateinit var serializer: Serializer<ForkId>

  @Mock
  private lateinit var hasher: Hasher

  private val chainId: UInt = 1u
  private val forkSpec1 = ForkSpec(10UL, 1U, mock<ConsensusConfig>())
  private val forkSpec2 = ForkSpec(20UL, 1U, mock<ConsensusConfig>())
  private val forkSpec3 = ForkSpec(30UL, 1U, mock<ConsensusConfig>())
  private val forkIdHasher by lazy { ForkIdHasher(serializer, hasher) }
  private val genesisHash = initialBeaconState.beaconBlockHeader.hash
  private var clock = Clock.fixed(Instant.ofEpochSecond(20), ZoneOffset.UTC)

  @BeforeEach
  fun setup() {
    MockitoAnnotations.openMocks(this)
    // we use a constant clock (20), so for the initialization we need to use that timestamp
    `when`(forksSchedule.getPreviousForkByTimestamp(20UL)).thenReturn(forkSpec1)
    `when`(forksSchedule.getForkByTimestamp(20UL)).thenReturn(forkSpec2)
    `when`(forksSchedule.getNextForkByTimestamp(20UL)).thenReturn(forkSpec3)

    // Stub for all ForkId values used in the test
    val forkId1 = ForkId(chainId, forkSpec1, genesisHash)
    val forkId2 = ForkId(chainId, forkSpec2, genesisHash)
    val forkId3 = ForkId(chainId, forkSpec3, genesisHash)
    `when`(serializer.serialize(forkId1)).thenReturn(ByteArray(32) { 1 })
    `when`(serializer.serialize(forkId2)).thenReturn(ByteArray(32) { 2 })
    `when`(serializer.serialize(forkId3)).thenReturn(ByteArray(32) { 3 })
    `when`(hasher.hash(ByteArray(32) { 1 })).thenReturn(ByteArray(4) { 1 })
    `when`(hasher.hash(ByteArray(32) { 2 })).thenReturn(ByteArray(4) { 2 })
    `when`(hasher.hash(ByteArray(32) { 3 })).thenReturn(ByteArray(4) { 3 })
  }

  @Test
  fun `currentHash returns correct hash for current fork`() {
    val manager = ForkIdHashManagerImpl(chainId, beaconChain, forksSchedule, forkIdHasher, clock)
    val hash = manager.currentHash()
    assertThat(hash).isEqualTo(ByteArray(4) { 2 })
  }

  @Test
  fun `check returns true for current fork id hash`() {
    val manager = ForkIdHashManagerImpl(chainId, beaconChain, forksSchedule, forkIdHasher, clock)
    val currentHash = manager.currentHash()
    assertThat(manager.check(currentHash)).isTrue()
  }

  @Test
  fun `check returns false for unknown fork id hash`() {
    val manager = ForkIdHashManagerImpl(chainId, beaconChain, forksSchedule, forkIdHasher, clock)
    val unknownHash = ByteArray(4) { 9 }
    assertThat(manager.check(unknownHash)).isFalse
  }

  @Test
  fun `update changes current fork id hash to next fork`() {
    val manager = ForkIdHashManagerImpl(chainId, beaconChain, forksSchedule, forkIdHasher, clock)

    manager.update(forkSpec3)
    assertThat(manager.currentHash()).isEqualTo(ByteArray(4) { 3 })
  }

  @Test
  fun `check returns true for previous fork id hash within allowed time window`() {
    val manager = ForkIdHashManagerImpl(chainId, beaconChain, forksSchedule, forkIdHasher, clock)

    // Get previous fork id hash
    val previousForkId = ForkId(chainId, forkSpec1, genesisHash)
    val previousForkIdHash = forkIdHasher.hash(previousForkId)

    // Should be within allowed window, as clock is constant and at timestamp for the current fork
    assertThat(manager.check(previousForkIdHash)).isTrue
  }

  @Test
  fun `check returns true for next fork id hash within allowed time window`() {
    val clock = mock<Clock>()
    `when`(clock.instant()).thenReturn(Instant.ofEpochSecond(20), Instant.ofEpochSecond(29))
    val manager = ForkIdHashManagerImpl(chainId, beaconChain, forksSchedule, forkIdHasher, clock)

    // Get previous fork id hash
    val nextForkId = ForkId(chainId, forkSpec3, genesisHash)
    val nextForkIdHash = forkIdHasher.hash(nextForkId)

    // Should be within allowed window
    assertThat(manager.check(nextForkIdHash)).isTrue
  }

  @Test
  fun `check returns false for next fork id hash not within allowed time window`() {
    val clock = mock<Clock>()
    `when`(clock.instant()).thenReturn(Instant.ofEpochSecond(20), Instant.ofEpochSecond(21))
    val manager = ForkIdHashManagerImpl(chainId, beaconChain, forksSchedule, forkIdHasher, clock)

    // Get previous fork id hash
    val nextForkId = ForkId(chainId, forkSpec3, genesisHash)
    val nextForkIdHash = forkIdHasher.hash(nextForkId)

    // Should be outside the allowed window
    assertThat(manager.check(nextForkIdHash)).isFalse
  }
}
