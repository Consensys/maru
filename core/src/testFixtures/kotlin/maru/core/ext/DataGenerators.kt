/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
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
import maru.executionlayer.manager.ExecutionPayloadStatus
import maru.executionlayer.manager.ForkChoiceUpdatedResult
import maru.executionlayer.manager.PayloadStatus
import maru.serialization.rlp.RLPSerializers
import maru.serialization.rlp.bodyRoot
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.crypto.SECP256K1
import org.hyperledger.besu.datatypes.Address
import org.hyperledger.besu.datatypes.TransactionType
import org.hyperledger.besu.datatypes.Wei
import org.hyperledger.besu.ethereum.core.Transaction

object DataGenerators {
  fun randomBeaconState(
    number: ULong,
    timestamp: ULong = Random.nextULong(),
  ): BeaconState {
    val validators = randomValidators()
    val beaconBlockHeader =
      BeaconBlockHeader(
        number = number,
        round = Random.nextUInt(),
        timestamp = timestamp,
        proposer = validators.random(),
        parentRoot = Random.nextBytes(32),
        stateRoot = Random.nextBytes(32),
        bodyRoot = Random.nextBytes(32),
        headerHashFunction = RLPSerializers.DefaultHeaderHashFunction,
      )
    return BeaconState(
      latestBeaconBlockHeader = beaconBlockHeader,
      validators = validators,
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
        (1..3)
          .map {
            Seal(Random.nextBytes(96))
          }.toSet(),
    )

  fun randomBeaconBlockBody(numSeals: Int = 3): BeaconBlockBody =
    BeaconBlockBody(
      prevCommitSeals = (1..numSeals).map { Seal(Random.nextBytes(96)) }.toSet(),
      executionPayload = randomExecutionPayload(),
    )

  fun randomBeaconBlockHeader(
    number: ULong,
    proposer: Validator = Validator(Random.nextBytes(20)),
  ): BeaconBlockHeader =
    BeaconBlockHeader(
      number = number,
      round = Random.nextUInt(),
      timestamp = Random.nextULong(),
      proposer = proposer,
      parentRoot = Random.nextBytes(32),
      stateRoot = Random.nextBytes(32),
      bodyRoot = Random.nextBytes(32),
      headerHashFunction = RLPSerializers.DefaultHeaderHashFunction,
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

  fun randomValidForkChoiceUpdatedResult(payloadId: ByteArray? = Random.nextBytes(8)): ForkChoiceUpdatedResult {
    val expectedPayloadStatus =
      PayloadStatus(
        ExecutionPayloadStatus.VALID,
        latestValidHash = Random.nextBytes(32),
        validationError = null,
      )
    return ForkChoiceUpdatedResult(expectedPayloadStatus, payloadId)
  }

  fun randomValidator(): Validator = Validator(Random.nextBytes(20))

  fun randomValidators(): Set<Validator> = List(3) { randomValidator() }.toSet()

  fun randomValidPayloadStatus(): PayloadStatus =
    PayloadStatus(ExecutionPayloadStatus.VALID, latestValidHash = Random.nextBytes(32), validationError = null)
}
