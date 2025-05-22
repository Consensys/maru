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

import maru.consensus.ValidatorProvider
import maru.consensus.state.FinalizationState
import maru.core.Validator
import maru.core.ext.DataGenerators
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.consensus.common.bft.blockcreation.ProposerSelector
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever

class QbftBlockCreatorFactoryTest {
  private val executionLayerManager = Mockito.mock(ExecutionLayerManager::class.java)
  private val proposerSelector = Mockito.mock(ProposerSelector::class.java)
  private val validatorProvider = Mockito.mock(ValidatorProvider::class.java)
  private val beaconChain = Mockito.mock(BeaconChain::class.java)
  private val finalizationState = Mockito.mock(FinalizationState::class.java)
  private val blockBuilderIdentity = Mockito.mock(Validator::class.java)
  private val eagerQbftBlockCreatorConfig = Mockito.mock(EagerQbftBlockCreator.Config::class.java)

  @Test
  fun `uses eager block creator when round zero and started building`() {
    whenever(beaconChain.getLatestBeaconState()).thenReturn(
      DataGenerators.randomBeaconState(0u),
    )
    whenever(
      executionLayerManager.hasStartedBlockBuilding(),
    ).thenReturn(false)

    val qbftBlockCreatorFactory =
      QbftBlockCreatorFactory(
        manager = executionLayerManager,
        proposerSelector = proposerSelector,
        validatorProvider = validatorProvider,
        beaconChain = beaconChain,
        finalizationStateProvider = { (_) -> finalizationState },
        blockBuilderIdentity = blockBuilderIdentity,
        eagerQbftBlockCreatorConfig = eagerQbftBlockCreatorConfig,
      )

    val blockCreator = qbftBlockCreatorFactory.create(0)
    assertThat(blockCreator).isInstanceOf(EagerQbftBlockCreator::class.java)
  }

  @Test
  fun `uses eager block creator when round greater than zero`() {
    whenever(beaconChain.getLatestBeaconState()).thenReturn(
      DataGenerators.randomBeaconState(0u),
    )
    whenever(
      executionLayerManager.hasStartedBlockBuilding(),
    ).thenReturn(false)

    val qbftBlockCreatorFactory =
      QbftBlockCreatorFactory(
        manager = executionLayerManager,
        proposerSelector = proposerSelector,
        validatorProvider = validatorProvider,
        beaconChain = beaconChain,
        finalizationStateProvider = { (_) -> finalizationState },
        blockBuilderIdentity = blockBuilderIdentity,
        eagerQbftBlockCreatorConfig = eagerQbftBlockCreatorConfig,
      )

    val blockCreator = qbftBlockCreatorFactory.create(1)
    assertThat(blockCreator).isInstanceOf(EagerQbftBlockCreator::class.java)
  }

  @Test
  fun `uses delayed block creator when round 0 and started building`() {
    whenever(beaconChain.getLatestBeaconState()).thenReturn(
      DataGenerators.randomBeaconState(0u),
    )
    whenever(
      executionLayerManager.hasStartedBlockBuilding(),
    ).thenReturn(true)

    val qbftBlockCreatorFactory =
      QbftBlockCreatorFactory(
        manager = executionLayerManager,
        proposerSelector = proposerSelector,
        validatorProvider = validatorProvider,
        beaconChain = beaconChain,
        finalizationStateProvider = { (_) -> finalizationState },
        blockBuilderIdentity = blockBuilderIdentity,
        eagerQbftBlockCreatorConfig = eagerQbftBlockCreatorConfig,
      )

    val blockCreator = qbftBlockCreatorFactory.create(0)
    assertThat(blockCreator).isInstanceOf(DelayedQbftBlockCreator::class.java)
  }
}
