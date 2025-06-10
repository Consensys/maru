/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.database.kv

import maru.serialization.Serializer
import tech.pegasys.teku.storage.server.kvstore.serialization.KvStoreSerializer

class KvStoreSerializerAdapter<T>(
  private val serializer: Serializer<T>,
) : KvStoreSerializer<T> {
  override fun deserialize(bytes: ByteArray): T = serializer.deserialize(bytes)

  override fun serialize(value: T): ByteArray = serializer.serialize(value)
}
