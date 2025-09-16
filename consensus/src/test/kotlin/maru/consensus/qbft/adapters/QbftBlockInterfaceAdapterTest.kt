/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft.adapters

import maru.consensus.ValidatorProvider
import maru.consensus.state.StateTransitionImpl
import maru.core.BeaconBlock
import maru.core.ext.DataGenerators
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture

class QbftBlockInterfaceAdapterTest {
  @Test
  fun `can replace round number in header`() {
    val beaconBlock =
      BeaconBlock(
        beaconBlockHeader = DataGenerators.randomBeaconBlockHeader(1UL).copy(round = 10u),
        beaconBlockBody = DataGenerators.randomBeaconBlockBody(),
      )
    val qbftBlock = QbftBlockAdapter(beaconBlock)
    val stateTransition = createMockStateTransition()
    val adapter = QbftBlockInterfaceAdapter(stateTransition)
    val updatedBlock =
      adapter.replaceRoundInBlock(qbftBlock, 20)
    val updatedBeaconBlockHeader = updatedBlock.header.toBeaconBlockHeader()
    assertEquals(updatedBeaconBlockHeader.round, 20u)
  }

  private fun createMockStateTransition(): StateTransitionImpl {
    val validatorProvider = mock<ValidatorProvider>()
    val validators = DataGenerators.randomValidators()
    whenever(validatorProvider.getValidatorsForBlock(any()))
      .thenReturn(SafeFuture.completedFuture(validators))
    return StateTransitionImpl(validatorProvider = validatorProvider)
  }
}
