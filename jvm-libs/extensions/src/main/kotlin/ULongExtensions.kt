/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.extensions

fun ULong.toBytes32(): ByteArray {
  // Create a 32-byte array initialized with zeros
  val bytes = ByteArray(32)
  // Convert ULong to ByteArray (8 bytes in big-endian)
  val longBytes = this.toByteArray()
  // Copy the 8 bytes of ULong into the last 8 bytes of the 32-byte array
  longBytes.copyInto(destination = bytes, destinationOffset = 24) // 32 - 8 = 24
  return bytes
}

// Helper function to convert ULong to ByteArray (big-endian)
private fun ULong.toByteArray(): ByteArray =
  ByteArray(ULong.SIZE_BYTES) { i ->
    ((this shr ((ULong.SIZE_BYTES - 1 - i) * Byte.SIZE_BITS)) and 0xFFu).toByte()
  }

// Ported from the Besu Word.clampedAdd method for Long values
fun ULong.clampedAdd(other: ULong): ULong {
    val r = this + other
    if (((this xor r) and (other xor r)) < 0UL) {
      // out of bounds, clamp it!
      return if (this > 0UL) ULong.MAX_VALUE else ULong.MIN_VALUE
    } else {
      return r
    }
}
