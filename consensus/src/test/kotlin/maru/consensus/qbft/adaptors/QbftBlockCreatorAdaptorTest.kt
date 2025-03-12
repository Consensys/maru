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
package maru.consensus.qbft.adaptors

import java.math.BigInteger
import maru.consensus.BlockCreator
import maru.core.Seal
import maru.core.ext.DataGenerators
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.consensus.qbft.core.types.QbftExtraDataProvider
import org.hyperledger.besu.crypto.SECPSignature
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever

class QbftBlockCreatorAdaptorTest {
  private val blockCreator = Mockito.mock(BlockCreator::class.java)
  private val extraDataProvider = Mockito.mock(QbftExtraDataProvider::class.java)

  @Test
  fun `can create a block`() {
    val parentBeaconHeader = DataGenerators.randomBeaconBlockHeader(10U)
    val createdBeaconBlock = DataGenerators.randomBeaconBlock(11U)

    whenever(blockCreator.createBlock(1000L, 1, parentBeaconHeader)).thenReturn(createdBeaconBlock)
    val qbftBlockCreatorAdaptor = QbftBlockCreatorAdaptor(blockCreator, 1)

    val createdQbftblock = qbftBlockCreatorAdaptor.createBlock(1000L, QbftBlockHeaderAdaptor(parentBeaconHeader))
    assertThat(BlockUtil.toBeaconBlock(createdQbftblock)).isEqualTo(createdBeaconBlock)
  }

  @Test
  fun createSealedBlock() {
    val block = DataGenerators.randomBeaconBlock(11U)
    val qbftBlock = QbftBlockAdaptor(block)
    val seals = listOf(SECPSignature.create(BigInteger.ONE, BigInteger.TWO, 0x00, BigInteger.valueOf(4)))
    val round = 12

    whenever(
      blockCreator.createSealedBlock(
        block,
        12,
        seals.map {
          Seal(it.encodedBytes().toArray())
        },
      ),
    ).thenReturn(DataGenerators.randomSealedBeaconBlock(round.toULong()))
    val qbftBlockCreatorAdaptor = QbftBlockCreatorAdaptor(blockCreator, 1)

    val sealedBlock = qbftBlockCreatorAdaptor.createSealedBlock(extraDataProvider, qbftBlock, round, seals)
    assertThat(sealedBlock)
  }
}
