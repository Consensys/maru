/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.extensions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ULongExtensionsTest {
  @Test
  fun `clampedAdd returns sum when no overflow`() {
    val a = 10uL
    val b = 20uL
    val result = a.clampedAdd(b)
    assertThat(result).isEqualTo(30uL)
  }

  @Test
  fun `clampedAdd returns ULong_MAX_VALUE on overflow`() {
    val a = ULong.MAX_VALUE
    val b = 1uL
    val result = a.clampedAdd(b)
    assertThat(result).isEqualTo(ULong.MAX_VALUE)
  }

  @Test
  fun `clampedAdd returns ULong_MAX_VALUE when both operands are large`() {
    val a = ULong.MAX_VALUE - 1uL
    val b = ULong.MAX_VALUE - 2uL
    val result = a.clampedAdd(b)
    assertThat(result).isEqualTo(ULong.MAX_VALUE)
  }

  @Test
  fun `clampedAdd works with zero`() {
    val a = 0uL
    val b = 0uL
    val result = a.clampedAdd(b)
    assertThat(result).isEqualTo(0uL)
  }
}
