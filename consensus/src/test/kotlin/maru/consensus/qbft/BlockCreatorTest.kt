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

import java.math.BigInteger
import java.util.Collections
import java.util.Optional
import maru.consensus.qbft.adaptors.BlockUtil
import maru.consensus.qbft.adaptors.QbftBlockHeaderAdaptor
import maru.core.BeaconState
import maru.core.HashUtil
import maru.core.Seal
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.executionlayer.manager.ExecutionLayerManager
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.consensus.common.bft.BftExtraData
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.blockcreation.ProposerSelector
import org.hyperledger.besu.consensus.qbft.core.types.QbftExtraDataProvider
import org.hyperledger.besu.consensus.qbft.core.types.QbftValidatorProvider
import org.hyperledger.besu.crypto.SECPSignature
import org.hyperledger.besu.datatypes.Address
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture

class BlockCreatorTest {
  private var executionLayerManager = Mockito.mock(ExecutionLayerManager::class.java)
  private var proposerSelector = Mockito.mock(ProposerSelector::class.java)
  private var validatorProvider = Mockito.mock(QbftValidatorProvider::class.java)
  private var extraDataProvider = Mockito.mock(QbftExtraDataProvider::class.java)

  @Test
  fun `can create block`() {
    val parentHeader = QbftBlockHeaderAdaptor(DataGenerators.randomBeaconBlockHeader(10U))
    val parentSeals = listOf(SECPSignature.create(BigInteger.ONE, BigInteger.TWO, 0x00, BigInteger.valueOf(4)))
    val bftExtraData =
      BftExtraData(
        Bytes.EMPTY,
        parentSeals,
        Optional.empty(),
        0,
        Collections.emptyList(),
      )
    val executionPayload = DataGenerators.randomExecutionPayload()

    whenever(extraDataProvider.getExtraData(parentHeader)).thenReturn(bftExtraData)
    whenever(executionLayerManager.finishBlockBuilding()).thenReturn(SafeFuture.completedFuture(executionPayload))
    whenever(proposerSelector.selectProposerForRound(ConsensusRoundIdentifier(11L, 1))).thenReturn(Address.ZERO)

    val blockCreator = BlockCreator(executionLayerManager, proposerSelector, validatorProvider, extraDataProvider, 1UL)

    val newBlock = blockCreator.createBlock(1000L, parentHeader)
    val beaconBlock = BlockUtil.toBeaconBlock(newBlock!!)

    val beaconState =
      BeaconState(
        beaconBlock.beaconBlockHeader.copy(stateRoot = ByteArray(32)),
        HashUtil.bodyRoot(beaconBlock.beaconBlockBody),
        Collections.emptySet<Validator>(),
      )
    val stateRoot = HashUtil.stateRoot(beaconState)

    assertNotNull(beaconBlock)
    assertThat(beaconBlock.beaconBlockHeader.number).isEqualTo(11UL)
    assertThat(beaconBlock.beaconBlockHeader.round).isEqualTo(1UL)
    assertThat(beaconBlock.beaconBlockHeader.timestamp).isEqualTo(1000UL)
    assertThat(beaconBlock.beaconBlockHeader.proposer).isEqualTo(Validator(Address.ZERO.toArray()))
    assertThat(beaconBlock.beaconBlockHeader.parentRoot).isEqualTo(parentHeader.beaconBlockHeader.hash())
    assertThat(beaconBlock.beaconBlockHeader.bodyRoot).isEqualTo(HashUtil.bodyRoot(beaconBlock.beaconBlockBody))
    assertThat(beaconBlock.beaconBlockHeader.stateRoot).isEqualTo(stateRoot)
    assertThat(
      beaconBlock.beaconBlockHeader.hash(),
    ).isEqualTo(HashUtil.headerCommittedSealHash(beaconBlock.beaconBlockHeader))
    assertThat(
      beaconBlock.beaconBlockBody.prevCommitSeals,
    ).isEqualTo(parentSeals.map { Seal(it.encodedBytes().toArray()) })
    assertThat(beaconBlock.beaconBlockBody.commitSeals).isEqualTo(Collections.emptyList<Seal>())
    assertThat(beaconBlock.beaconBlockBody.executionPayload).isEqualTo(executionPayload)
  }
}
