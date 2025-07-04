/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import kotlin.random.Random
import maru.consensus.ForkId
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

class ForkIdTest {
  private val dummyChainId: UInt = 1u

  @Test
  fun `serialization changes when chain id changes`() {
    val forkId1 = ForkId(dummyChainId, Random.nextBytes(32))
    val forkId2 = forkId1.copy(chainId = 21U)
    Assertions.assertThat(forkId1.bytes).isNotEqualTo(forkId2.bytes)
  }

  @Test
  fun `serialization changes when genesis root hash changes`() {
    val forkId1 = ForkId(dummyChainId, Random.nextBytes(32))
    val forkId2 = forkId1.copy(genesisRootHash = Random.nextBytes(32))
    Assertions.assertThat(forkId1.bytes).isNotEqualTo(forkId2.bytes)
  }
}
