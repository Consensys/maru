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

import maru.serialization.rlp.RLPSerializers
import maru.serialization.rlp.RLPSerializers.BeaconBlockBodySerializer
import maru.serialization.rlp.RLPSerializers.BeaconBlockHeaderSerializer
import org.hyperledger.besu.datatypes.Hash
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput

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
    val headerWithoutRound = header.copy(round = 0u)
    val rlpOutput = BytesValueRLPOutput()
    BeaconBlockHeaderSerializer.writeTo(headerWithoutRound, rlpOutput)
    return Hash.hash(rlpOutput.encoded()).toArray()
  }

  /**
   * Hashes the header for the current commit seal hash including the round number
   */
  fun headerCommittedSealHash(header: BeaconBlockHeader): ByteArray {
    val rlpOutput = BytesValueRLPOutput()
    BeaconBlockHeaderSerializer.writeTo(header, rlpOutput)
    return Hash.hash(rlpOutput.encoded()).toArray()
  }

  fun bodyRoot(body: BeaconBlockBody): ByteArray {
    val bodyWithoutCommitSeals = body.copy(commitSeals = emptyList())
    val rlpOutput = BytesValueRLPOutput()
    BeaconBlockBodySerializer.writeTo(bodyWithoutCommitSeals, rlpOutput)
    return Hash.hash(rlpOutput.encoded()).toArray()
  }

  fun stateRoot(state: BeaconState): ByteArray {
    val rlpOutput = BytesValueRLPOutput()
    RLPSerializers.BeaconStateSerializer.writeTo(state, rlpOutput)
    return Hash.hash(rlpOutput.encoded()).toArray()
  }
}
