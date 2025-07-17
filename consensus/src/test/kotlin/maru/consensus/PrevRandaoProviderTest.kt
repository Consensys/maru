/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus

import kotlin.random.Random
import kotlin.test.Test
import maru.crypto.Hashing
import maru.extensions.encodeHex
import maru.extensions.xor
import org.assertj.core.api.Assertions.assertThat

class PrevRandaoProviderTest {
  @Test
  fun `calculateNextPrevRandao result XOR the signatureHash should return prevRandao`() {
    val signatureByteArray = Random.nextBytes(65)
    val signatureHash = Hashing.keccak(signatureByteArray)
    val prevRandaoProvider =
      PrevRandaoProviderImpl(
        signingFunc = { signatureByteArray },
      )
    val prevRandao = Random.nextBytes(32)

    val result = prevRandaoProvider.calculateNextPrevRandao(100UL, prevRandao)

    assertThat(result.xor(signatureHash).encodeHex())
      .isEqualTo(prevRandao.encodeHex())
  }
}
