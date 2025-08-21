/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.compression

import kotlin.random.Random
import maru.serialization.compression.MaruSnappyCompressor
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

class MaruSnappyCompressorTest {
  private val compressor = MaruSnappyCompressor()

  @Test
  fun `can compress and decompress same payload`() {
    val payload = Random.nextBytes(128)
    val compressedPayload = compressor.compress(payload)
    val decompressedPayload = compressor.decompress(compressedPayload)
    Assertions.assertThat(decompressedPayload).isEqualTo(payload)
  }

  @Test
  fun `can compress and decompress zero size payload`() {
    val payload = ByteArray(0)
    val compressedPayload = compressor.compress(payload)
    val decompressedPayload = compressor.decompress(compressedPayload)
    Assertions.assertThat(decompressedPayload).isEqualTo(payload)
  }
}
