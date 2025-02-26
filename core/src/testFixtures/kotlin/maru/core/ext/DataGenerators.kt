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
package maru.core.ext

import java.math.BigInteger
import kotlin.random.Random
import kotlin.random.nextULong
import maru.core.BeaconBlock
import maru.core.BeaconBlockBody
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.ExecutionPayload
import maru.core.HashUtil
import maru.core.Seal
import maru.core.Validator
import maru.serialization.rlp.RLPOnChainSerializers

object DataGenerators {
  fun randomBeaconState(number: ULong): BeaconState {
    val beaconBlockHeader =
      BeaconBlockHeader(
        number = number,
        round = Random.nextULong(),
        timestamp = Random.nextULong(),
        proposer = Validator(Random.nextBytes(128)),
        parentRoot = Random.nextBytes(32),
        stateRoot = Random.nextBytes(32),
        bodyRoot = Random.nextBytes(32),
        HashUtil.headerOnChainHash(RLPOnChainSerializers.BeaconBlockHeaderSerializer),
      )
    return BeaconState(
      latestBeaconBlockHeader = beaconBlockHeader,
      latestBeaconBlockRoot = Random.nextBytes(32),
      validators = buildSet(3) { Validator(Random.nextBytes(128)) },
    )
  }

  fun randomBeaconBlock(number: ULong): BeaconBlock {
    val beaconBlockHeader = randomBeaconBlockHeader(number)
    val beaconBlockBody = randomBeaconBlockBody()
    return BeaconBlock(
      beaconBlockHeader = beaconBlockHeader,
      beaconBlockBody = beaconBlockBody,
    )
  }

  fun randomBeaconBlockBody(): BeaconBlockBody =
    BeaconBlockBody(
      prevCommitSeals = (1..3).map { Seal(Random.nextBytes(96)) },
      commitSeals = (1..3).map { Seal(Random.nextBytes(96)) },
      executionPayload = randomExecutionPayload(),
    )

  fun randomBeaconBlockHeader(number: ULong): BeaconBlockHeader =
    BeaconBlockHeader(
      number = number,
      round = Random.nextULong(),
      timestamp = Random.nextULong(),
      proposer = Validator(Random.nextBytes(128)),
      parentRoot = Random.nextBytes(32),
      stateRoot = Random.nextBytes(32),
      bodyRoot = Random.nextBytes(32),
      HashUtil.headerOnChainHash(RLPOnChainSerializers.BeaconBlockHeaderSerializer),
    )

  fun randomExecutionPayload(): ExecutionPayload =
    ExecutionPayload(
      parentHash = Random.nextBytes(32),
      feeRecipient = Random.nextBytes(20),
      stateRoot = Random.nextBytes(32),
      receiptsRoot = Random.nextBytes(32),
      logsBloom = Random.nextBytes(256),
      prevRandao = Random.nextBytes(32),
      blockNumber = Random.nextULong(),
      gasLimit = Random.nextULong(),
      gasUsed = Random.nextULong(),
      timestamp = Random.nextULong(),
      extraData = Random.nextBytes(32),
      baseFeePerGas = BigInteger.valueOf(Random.nextLong(0, Long.MAX_VALUE)),
      blockHash = Random.nextBytes(32),
      transactions = emptyList(),
    )
}
