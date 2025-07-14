/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus

import maru.crypto.Hashing
import maru.extensions.encodeHex
import maru.extensions.xor
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

fun interface PrevRandaoProvider {
  fun calculateNextPrevRandao(
    nextSlotId: ULong,
    prevRandao: ByteArray,
  ): ByteArray
}

class PrevRandaoProviderImpl(
  private val signingFunc: (ULong) -> ByteArray,
) : PrevRandaoProvider {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  override fun calculateNextPrevRandao(
    nextSlotId: ULong,
    prevRandao: ByteArray,
  ): ByteArray {
    val signedSlotId = signingFunc(nextSlotId)
    val signedSlotIdHash = Hashing.keccak(signedSlotId)
    val nextPrevRandao = prevRandao.xor(signedSlotIdHash)

    log.debug(
      "calculateNextPrevRandao: nextSlotId=$nextSlotId prevRandao=${prevRandao.encodeHex()}" +
        " signedSlotId=${signedSlotId.encodeHex()} signedSlotIdHash=${signedSlotIdHash.encodeHex()} nextPrevRandao=${nextPrevRandao.encodeHex()}",
    )

    return nextPrevRandao
  }
}
