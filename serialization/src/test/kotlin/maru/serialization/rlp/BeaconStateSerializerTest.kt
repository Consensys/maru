/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.serialization.rlp

import kotlin.random.Random
import kotlin.random.nextULong
import maru.core.BeaconState
import maru.core.HashUtil
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.crypto.Hashing
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BeaconStateSerializerTest {
  private val validatorSerializer = ValidatorSerDe()
  private val beaconBlockHeaderSerializer =
    BeaconBlockHeaderSerDe(
      validatorSerializer = validatorSerializer,
      hasher = Hashing::keccak,
      headerHashFunction = HashUtil::headerHash,
    )
  private val serializer =
    BeaconStateSerDe(
      beaconBlockHeaderSerializer = beaconBlockHeaderSerializer,
      validatorSerializer = validatorSerializer,
    )

  @Test
  fun `can serialize and deserialize same value`() {
    val beaconBLockHeader = DataGenerators.randomBeaconBlockHeader(Random.nextULong())
    val testValue =
      BeaconState(
        latestBeaconBlockHeader = beaconBLockHeader,
        validators = buildSet(3) { Validator(Random.nextBytes(20)) },
      )
    val serializedData = serializer.serialize(testValue)
    val deserializedValue = serializer.deserialize(serializedData)

    assertThat(deserializedValue).isEqualTo(testValue)
  }
}
