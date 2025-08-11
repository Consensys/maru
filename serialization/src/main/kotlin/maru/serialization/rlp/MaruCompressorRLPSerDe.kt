/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.serialization.rlp

import maru.compression.MaruCompressor

abstract class MaruCompressorRLPSerDe<T>(
  private val compressor: MaruCompressor,
) : RLPSerDe<T> {
  override fun serialize(value: T): ByteArray = compressor.compress(super.serialize(value))

  override fun deserialize(bytes: ByteArray): T = super.deserialize(compressor.decompress(bytes))
}
