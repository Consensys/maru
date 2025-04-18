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
import kotlin.random.nextUInt
import kotlin.random.nextULong
import maru.core.BeaconBlock
import maru.core.BeaconBlockBody
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.ExecutionPayload
import maru.core.HashUtil
import maru.core.Seal
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.executionlayer.manager.BlockMetadata
import maru.executionlayer.manager.ForkChoiceUpdatedResult
import maru.executionlayer.manager.PayloadStatus
import maru.serialization.rlp.KeccakHasher
import maru.serialization.rlp.RLPSerializers
import maru.serialization.rlp.bodyRoot
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.crypto.SECP256K1
import org.hyperledger.besu.datatypes.Address
import org.hyperledger.besu.datatypes.TransactionType
import org.hyperledger.besu.datatypes.Wei
import org.hyperledger.besu.ethereum.core.Transaction

object DataGenerators {
  private val HEADER_HASH_FUNCTION = HashUtil.headerHash(RLPSerializers.BeaconBlockHeaderSerializer, KeccakHasher)

  fun randomBeaconState(number: ULong): BeaconState {
    val beaconBlockHeader =
      BeaconBlockHeader(
        number = number,
        round = Random.nextUInt(),
        timestamp = Random.nextULong(),
        proposer = Validator(Random.nextBytes(128)),
        parentRoot = Random.nextBytes(32),
        stateRoot = Random.nextBytes(32),
        bodyRoot = Random.nextBytes(32),
        HEADER_HASH_FUNCTION,
      )
    return BeaconState(
      latestBeaconBlockHeader = beaconBlockHeader,
      validators = buildSet(3) { Validator(Random.nextBytes(128)) },
    )
  }

  fun randomBeaconBlock(number: ULong): BeaconBlock {
    val beaconBlockBody = randomBeaconBlockBody()
    val beaconBlockHeader =
      randomBeaconBlockHeader(number).copy(
        bodyRoot = HashUtil.bodyRoot(beaconBlockBody),
      )
    return BeaconBlock(
      beaconBlockHeader = beaconBlockHeader,
      beaconBlockBody = beaconBlockBody,
    )
  }

  fun randomSealedBeaconBlock(number: ULong): SealedBeaconBlock =
    SealedBeaconBlock(
      beaconBlock = randomBeaconBlock(number),
      commitSeals =
        (1..3).map {
          Seal(Random.nextBytes(96))
        },
    )

  fun randomBeaconBlockBody(numSeals: Int = 3): BeaconBlockBody =
    BeaconBlockBody(
      prevCommitSeals = (1..numSeals).map { Seal(Random.nextBytes(96)) },
      executionPayload = randomExecutionPayload(),
    )

  fun randomBeaconBlockHeader(number: ULong): BeaconBlockHeader =
    BeaconBlockHeader(
      number = number,
      round = Random.nextUInt(),
      timestamp = Random.nextULong(),
      proposer = Validator(Random.nextBytes(128)),
      parentRoot = Random.nextBytes(32),
      stateRoot = Random.nextBytes(32),
      bodyRoot = Random.nextBytes(32),
      headerHashFunction = HEADER_HASH_FUNCTION,
    )

  fun randomExecutionPayload(numberOfTransactions: Int = 5): ExecutionPayload {
    val transactions =
      (1..numberOfTransactions).map {
        Transaction
          .builder()
          .type(TransactionType.FRONTIER)
          .chainId(BigInteger.valueOf(Random.nextLong(0, 10000L)))
          .nonce(Random.nextLong(0, 1000L))
          .gasPrice(Wei.of(Random.nextLong(0, 1000000L)))
          .gasLimit(Random.nextLong(0, 1000000L))
          .to(Address.wrap(Bytes.wrap(Random.nextBytes(20))))
          .value(Wei.of(Random.nextLong(0, 1000L)))
          .payload(Bytes.wrap(Bytes.wrap(Random.nextBytes(32))))
          .signAndBuild(
            SECP256K1().generateKeyPair(),
          ).encoded()
          .toArray()
      }
    return ExecutionPayload(
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
      transactions = transactions,
    )
  }

  fun randomBlockMetadata(timestamp: Long): BlockMetadata =
    BlockMetadata(
      Random.nextULong(),
      blockHash = Random.nextBytes(32),
      unixTimestampSeconds = timestamp,
    )

  fun randomValidForkChoiceUpdatedResult(payloadId: ByteArray? = Random.nextBytes(8)): ForkChoiceUpdatedResult {
    val expectedPayloadStatus =
      PayloadStatus(
        executionPayloadStatus = "VALID",
        latestValidHash = Random.nextBytes(32),
        validationError = null,
        failureCause = null,
      )
    return ForkChoiceUpdatedResult(expectedPayloadStatus, payloadId)
  }

  fun randomValidator(): Validator = Validator(Random.nextBytes(20))

  fun randomValidators(): Set<Validator> =
    buildSet(3) {
      add(
        Validator(
          Random
            .nextBytes(20),
        ),
      )
    }
}
