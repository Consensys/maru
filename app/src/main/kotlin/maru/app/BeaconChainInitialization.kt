/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import java.math.BigInteger
import maru.core.BeaconBlock
import maru.core.BeaconBlockBody
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.EMPTY_HASH
import maru.core.ExecutionPayload
import maru.core.HashUtil
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.database.BeaconChain
import maru.serialization.rlp.RLPSerializers
import maru.serialization.rlp.stateRoot

class BeaconChainInitialization(
  private val beaconChain: BeaconChain,
  private val genesisTimestamp: ULong = 0UL,
) {
  private fun initializeDb(validatorSet: Set<Validator>) {
    val genesisExecutionPayload =
      ExecutionPayload(
        parentHash = EMPTY_HASH,
        feeRecipient = EMPTY_HASH.copyOfRange(0, 20), // 20 bytes for address
        stateRoot = EMPTY_HASH,
        receiptsRoot = EMPTY_HASH,
        logsBloom = ByteArray(256), // Ethereum logs bloom is 256 bytes
        prevRandao = EMPTY_HASH,
        blockNumber = 0UL,
        gasLimit = 30000000UL, // Default gas limit
        gasUsed = 0UL,
        timestamp = 0UL,
        extraData = ByteArray(0),
        baseFeePerGas = BigInteger.ZERO,
        blockHash = EMPTY_HASH,
        transactions = emptyList(),
      )

    val beaconBlockBody = BeaconBlockBody(prevCommitSeals = emptySet(), executionPayload = genesisExecutionPayload)

    val beaconBlockHeader =
      BeaconBlockHeader(
        number = 0u,
        round = 0u,
        timestamp = genesisTimestamp,
        proposer = Validator(genesisExecutionPayload.feeRecipient),
        parentRoot = EMPTY_HASH,
        stateRoot = EMPTY_HASH,
        bodyRoot = EMPTY_HASH,
        headerHashFunction = RLPSerializers.DefaultHeaderHashFunction,
      )

    val tmpGenesisStateRoot =
      BeaconState(
        latestBeaconBlockHeader = beaconBlockHeader,
        validators = validatorSet,
      )
    val stateRootHash = HashUtil.stateRoot(tmpGenesisStateRoot)

    val genesisBlockHeader = beaconBlockHeader.copy(stateRoot = stateRootHash)
    val genesisBlock = BeaconBlock(genesisBlockHeader, beaconBlockBody)
    val genesisState = BeaconState(genesisBlockHeader, validatorSet)
    beaconChain.newUpdater().run {
      putBeaconState(genesisState)
      putSealedBeaconBlock(SealedBeaconBlock(genesisBlock, emptySet()))
      commit()
    }
  }

  fun ensureDbIsInitialized(validatorSet: Set<Validator>) {
    if (!beaconChain.isInitialized()) {
      initializeDb(validatorSet)
    }
  }
}
