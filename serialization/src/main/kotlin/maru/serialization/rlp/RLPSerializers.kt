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
package maru.serialization.rlp

import maru.core.HashUtil
import maru.crypto.Hashing

object RLPSerializers {
  val ValidatorSerializer = ValidatorSerDe()

  val BeaconBlockHeaderSerializer =
    BeaconBlockHeaderSerDe(
      validatorSerializer = ValidatorSerializer,
      hasher = Hashing::keccak,
      headerHashFunction = HashUtil::headerHash,
    )
  val SealSerializer = SealSerDe()
  val ExecutionPayloadSerializer = ExecutionPayloadSerDe()
  val BeaconBlockBodySerializer =
    BeaconBlockBodySerDe(
      sealSerializer = SealSerializer,
      executionPayloadSerializer = ExecutionPayloadSerializer,
    )
  val BeaconBlockSerializer =
    BeaconBlockSerDe(
      beaconBlockHeaderSerializer = BeaconBlockHeaderSerializer,
      beaconBlockBodySerializer = BeaconBlockBodySerializer,
    )
  val SealedBeaconBlockSerializer =
    SealedBeaconBlockSerDe(
      beaconBlockSerializer = BeaconBlockSerializer,
      sealSerializer = SealSerializer,
    )
  val BeaconStateSerializer =
    BeaconStateSerDe(
      beaconBlockHeaderSerializer = BeaconBlockHeaderSerializer,
      validatorSerializer = ValidatorSerializer,
    )
  val DefaultHeaderHashFunction = HashUtil.headerHash(BeaconBlockHeaderSerializer, Hashing::keccak)
}
