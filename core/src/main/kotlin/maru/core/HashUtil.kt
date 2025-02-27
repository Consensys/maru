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

import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.datatypes.Hash

typealias HashFunction = (BeaconBlockHeader) -> ByteArray

enum class HashType(
  val hashFunction: HashFunction,
) {
  ON_CHAIN(HashUtil::headerOnChainHash),
  COMMITTED_SEAL(HashUtil::headerCommittedSealHash),
}

/**
 * Utility class for hashing various parts of the beacon chain
 */
object HashUtil {
  /**
   * Hashes the header for onchain omitting the round number
   */
  fun headerOnChainHash(header: BeaconBlockHeader): ByteArray {
    val headerAsBytes =
      byteArrayOf(header.number.toByte()) + header.proposer.address + header.parentRoot + header.stateRoot +
        header.bodyRoot
    return Hash.hash(Bytes.wrap(headerAsBytes)).toArray()
  }

  /**
   * Hashes the header for the commit seal hash including the round number
   */
  fun headerCommittedSealHash(header: BeaconBlockHeader): ByteArray {
    val headerAsBytes =
      byteArrayOf(header.number.toByte()) + byteArrayOf(header.round.toByte()) + header.proposer.address +
        header.parentRoot +
        header.stateRoot +
        header.bodyRoot
    return Hash.hash(Bytes.wrap(headerAsBytes)).toArray()
  }

  fun bodyRoot(body: BeaconBlockBody): ByteArray {
    // this deliberately does not include commit seals as these are always excluded as part of the hash
    val prevCommitSeals = body.prevCommitSeals.map { it.signature }.reduceOrNull { acc, bytes -> acc + bytes }
    val bodyAsBytes =
      (prevCommitSeals ?: byteArrayOf()) + body.executionPayload.blockHash
    return Hash.hash(Bytes.wrap(bodyAsBytes)).toArray()
  }

  fun stateRoot(state: BeaconState): ByteArray {
    var validatorsAsBytes =
      state.validators
        .map { it.address }
        .reduceOrNull { acc, bytes -> acc + bytes }
    // onchain hash can be used as this is the latest finalized beacon block header already on chain
    var stateRootAsBytes =
      headerOnChainHash(state.latestBeaconBlockHeader) + state.latestBeaconBlockRoot +
        (validatorsAsBytes ?: byteArrayOf())
    return Hash
      .hash(
        Bytes.wrap(
          stateRootAsBytes,
        ),
      ).toArray()
  }
}
