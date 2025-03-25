/*
   Copyright 2025 Consensys Software Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package maru.database.kv

import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.units.bigints.UInt256
import tech.pegasys.teku.storage.server.kvstore.serialization.KvStoreSerializer

class ULongSerializer : KvStoreSerializer<ULong> {
  override fun deserialize(value: ByteArray): ULong = UInt256.fromBytes(Bytes.wrap(value)).toLong().toULong()

  override fun serialize(value: ULong): ByteArray = UInt256.valueOf(value.toLong()).toBytes().toArrayUnsafe()
}
