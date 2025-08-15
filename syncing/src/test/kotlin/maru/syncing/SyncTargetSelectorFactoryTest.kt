/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SyncTargetSelectorFactoryTest {
  @Test
  fun `should throw exception if granularity is zero`() {
    val exception =
      assertThrows<IllegalArgumentException> {
        SyncTargetSelectorFactory.create(
          SyncTargetSelectorFactory.Config(
            granularity = 0U,
          ),
        )
      }
    assertEquals("Granularity should not be 0!", exception.message)
  }

  @Test
  fun `should return HighestHeadTargetSelector if granularity is one`() {
    assertThat(
      SyncTargetSelectorFactory.create(
        SyncTargetSelectorFactory.Config(
          granularity = 1U,
        ),
      ),
    ).isInstanceOf(HighestHeadTargetSelector::class.java)
  }

  @Test
  fun `should return MostFrequentHeadTargetSelector if granularity is larger than one`() {
    assertThat(
      SyncTargetSelectorFactory.create(
        SyncTargetSelectorFactory.Config(
          granularity = 10U,
        ),
      ),
    ).isInstanceOf(MostFrequentHeadTargetSelector::class.java)
  }
}
