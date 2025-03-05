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
package maru.core

import maru.serialization.Serializer
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.datatypes.Hash

typealias HeaderHashFunction = (BeaconBlockHeader) -> ByteArray

/**
 * Utility class for hashing various parts of the beacon chain
 */
object HashUtil {
  fun headerHash(serializer: Serializer<BeaconBlockHeader>): HeaderHashFunction =
    { header -> headerHash(serializer, header) }

  fun headerHash(
    serializer: Serializer<BeaconBlockHeader>,
    header: BeaconBlockHeader,
  ): ByteArray {
    val headerBytes = Bytes.wrap(serializer.serialize(header))
    return Hash.hash(headerBytes).toArray()
  }

  fun bodyRoot(
    serializer: Serializer<BeaconBlockBody>,
    body: BeaconBlockBody,
  ): ByteArray {
    val bodyBytes = Bytes.wrap(serializer.serialize((body)))
    return Hash.hash(bodyBytes).toArray()
  }

  fun stateRoot(
    serializer: Serializer<BeaconState>,
    state: BeaconState,
  ): ByteArray {
    val stateBytes = Bytes.wrap(serializer.serialize(state))
    return Hash.hash(stateBytes).toArray()
  }
}
