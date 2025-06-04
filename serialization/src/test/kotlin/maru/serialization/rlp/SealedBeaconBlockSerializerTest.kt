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

import kotlin.random.Random
import kotlin.random.nextULong
import maru.core.BeaconBlock
import maru.core.BeaconBlockBody
import maru.core.HashUtil
import maru.core.Seal
import maru.core.SealedBeaconBlock
import maru.core.ext.DataGenerators
import maru.core.ext.DataGenerators.randomExecutionPayload
import maru.crypto.Hashing
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SealedBeaconBlockSerializerTest {
  private val blockHeaderSerializer =
    BeaconBlockHeaderSerDe(
      validatorSerializer = ValidatorSerDe(),
      hasher = Hashing::keccak,
      headerHashFunction = HashUtil::headerHash,
    )
  private val sealSerializer = SealSerDe()
  private val blockSerializer =
    BeaconBlockSerDe(
      beaconBlockHeaderSerializer =
      blockHeaderSerializer,
      beaconBlockBodySerializer =
        BeaconBlockBodySerDe(
          sealSerializer = sealSerializer,
          executionPayloadSerializer = ExecutionPayloadSerDe(),
        ),
    )
  private val sealedBlockSerializer =
    SealedBeaconBlockSerDe(
      beaconBlockSerializer = blockSerializer,
      sealSerializer = sealSerializer,
    )

  @Test
  fun `can serialize and deserialize same value`() {
    val beaconBlockHeader = DataGenerators.randomBeaconBlockHeader(Random.nextULong())
    val beaconBlockBody =
      BeaconBlockBody(
        prevCommitSeals = buildSet(3) { add(Seal(Random.nextBytes(96))) },
        executionPayload = randomExecutionPayload(),
      )
    val sealedBlock =
      SealedBeaconBlock(
        beaconBlock =
          BeaconBlock(
            beaconBlockHeader = beaconBlockHeader,
            beaconBlockBody = beaconBlockBody,
          ),
        commitSeals = buildSet(3) { add(Seal(Random.nextBytes(96))) },
      )
    val serializedData = sealedBlockSerializer.serialize(sealedBlock)
    val deserializedValue = sealedBlockSerializer.deserialize(serializedData)

    assertThat(deserializedValue).isEqualTo(sealedBlock)
  }
}
