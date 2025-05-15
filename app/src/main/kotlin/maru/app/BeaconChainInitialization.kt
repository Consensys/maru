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
package maru.app

import maru.core.BeaconBlock
import maru.core.BeaconBlockBody
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.HashUtil
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.database.BeaconChain
import maru.mappers.Mappers.toDomain
import maru.serialization.rlp.RLPSerializers
import maru.serialization.rlp.stateRoot
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter

class BeaconChainInitialization(
  private val executionLayerClient: Web3j,
  private val beaconChain: BeaconChain,
  private val validatorSet: Set<Validator>,
) {
  private fun initializeDb() {
    val genesisExecutionPayload =
      executionLayerClient
        .ethGetBlockByNumber(DefaultBlockParameter.valueOf("latest"), true)
        .send()
        .block
        .toDomain()

    val beaconBlockBody = BeaconBlockBody(prevCommitSeals = emptySet(), executionPayload = genesisExecutionPayload)

    val beaconBlockHeader =
      BeaconBlockHeader(
        number = 0u,
        round = 0u,
        timestamp = genesisExecutionPayload.timestamp,
        proposer = Validator(genesisExecutionPayload.feeRecipient),
        parentRoot = ByteArray(32),
        stateRoot = ByteArray(32),
        bodyRoot = ByteArray(32),
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
    val genesisStateRoot = BeaconState(genesisBlockHeader, validatorSet)
    beaconChain.newUpdater().run {
      putBeaconState(genesisStateRoot)
      putSealedBeaconBlock(SealedBeaconBlock(genesisBlock, emptySet()))
      commit()
    }
  }

  fun ensureDbIsInitialized() {
    if (!beaconChain.isInitialized()) {
      initializeDb()
    }
  }
}
