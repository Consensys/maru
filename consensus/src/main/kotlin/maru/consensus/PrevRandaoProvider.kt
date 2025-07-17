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
    nextL2BlockNumber: ULong,
    prevRandao: ByteArray,
  ): ByteArray
}

class PrevRandaoProviderImpl(
  private val signingFunc: (ULong) -> ByteArray,
  private val hashingFunc: (ByteArray) -> ByteArray = Hashing::keccak,
) : PrevRandaoProvider {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  override fun calculateNextPrevRandao(
    nextL2BlockNumber: ULong,
    prevRandao: ByteArray,
  ): ByteArray {
    val signedNumber = signingFunc(nextL2BlockNumber)
    val signedNumberHash = hashingFunc(signedNumber)
    val nextPrevRandao = prevRandao.xor(signedNumberHash)

    log.debug(
      "calculateNextPrevRandao: nextL2BlockNumber=$nextL2BlockNumber prevRandao=${prevRandao.encodeHex()} " +
        "signedNumber=${signedNumber.encodeHex()} signedNumberHash=${signedNumberHash.encodeHex()} " +
        "nextPrevRandao=${nextPrevRandao.encodeHex()}",
    )
    return nextPrevRandao
  }
}
