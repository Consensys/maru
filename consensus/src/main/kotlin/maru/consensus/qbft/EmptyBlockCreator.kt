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
package maru.consensus.qbft

import maru.consensus.qbft.adapters.toBeaconBlockHeader
import maru.consensus.state.FinalizationState
import maru.core.BeaconBlockHeader
import maru.core.Validator
import maru.executionlayer.manager.ExecutionLayerManager
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockHeader
import org.hyperledger.besu.crypto.SECPSignature
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockCreator as BesuQbftBlockCreator

class EmptyBlockCreator(
  private val manager: ExecutionLayerManager,
  private val delegate: BesuQbftBlockCreator,
  private val finalizationStateProvider: (BeaconBlockHeader) -> FinalizationState,
  private val blockBuilderIdentity: Validator,
) : BesuQbftBlockCreator {
  override fun createBlock(
    headerTimeStampSeconds: Long,
    parentHeader: QbftBlockHeader,
  ): QbftBlock {
    val beaconBlockHeader = parentHeader.toBeaconBlockHeader()
    val finalizedState = finalizationStateProvider(beaconBlockHeader)
    manager
      .setHeadAndStartBlockBuilding(
        manager.latestBlockMetadata().blockHash,
        finalizedState.safeBlockHash,
        finalizedState.finalizedBlockHash,
        headerTimeStampSeconds,
        blockBuilderIdentity.address,
      ).get()
    return delegate.createBlock(headerTimeStampSeconds, parentHeader)
  }

  override fun createSealedBlock(
    block: QbftBlock,
    roundNumber: Int,
    commitSeals: MutableCollection<SECPSignature>,
  ): QbftBlock = QbftBlockCreator.createSealedBlock(block, roundNumber, commitSeals)
}
