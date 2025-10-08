/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus

import java.nio.ByteBuffer
import kotlin.time.ExperimentalTime
import maru.core.Hasher
import maru.core.ObjHasher

data class ForkIdV2
  @OptIn(ExperimentalTime::class)
  constructor(
    val prevForkIdDigest: ByteArray,
    val forkSpecDigest: ByteArray,
  )

class ForkIdV2Digester(
  private val hasher: Hasher,
  private val serializer: (ForkIdV2) -> ByteArray = ::forkIdToBytes,
) : ObjHasher<ForkIdV2> {
  override fun hash(obj: ForkIdV2): ByteArray {
    val hash = hasher.hash(serializer(obj))
    return hash.sliceArray(hash.size - 4 until hash.size)
  }
}

fun forkIdToBytes(forkIdV2: ForkIdV2): ByteArray {
  val buffer =
    ByteBuffer
      .allocate(forkIdV2.prevForkIdDigest.size + forkIdV2.forkSpecDigest.size)
      .put(forkIdV2.prevForkIdDigest)
      .put(forkIdV2.forkSpecDigest)
  return buffer.array()
}
