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
import java.util.zip.CRC32
import org.apache.tuweni.bytes.Bytes

data class ForkId(
  val chainId: UInt,
  val genesisRootHash: ByteArray,
) {
  companion object {
    const val FORK_ID_FIELD_NAME = "mfid"
  }

  val bytes = computeBytes()

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ForkId

    if (chainId != other.chainId) return false
    if (!genesisRootHash.contentEquals(other.genesisRootHash)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = chainId.hashCode()
    result = 31 * result + genesisRootHash.contentHashCode()
    return result
  }

  private fun computeBytes(): Bytes {
    val array =
      ByteBuffer
        .allocate(36)
        .putInt(chainId.toInt())
        .put(genesisRootHash)
        .array()
    val crc32 = CRC32()
    crc32.update(array)
    val bytesArray = ByteBuffer.allocate(8).putLong(crc32.value).array()
    return Bytes.wrap(bytesArray)
  }
}
