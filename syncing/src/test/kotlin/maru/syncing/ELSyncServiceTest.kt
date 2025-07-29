/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import kotlin.time.Duration.Companion.seconds
import maru.core.ext.DataGenerators
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import maru.executionlayer.manager.ExecutionPayloadStatus
import maru.executionlayer.manager.ForkChoiceUpdatedResult
import maru.executionlayer.manager.PayloadStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import tech.pegasys.teku.infrastructure.async.SafeFuture

class ELSyncServiceTest {
  @Test
  fun `should set sync status to Synced for genesis block`() {
    var elSyncStatus: ELSyncStatus? = null
    val onStatusChange: (ELSyncStatus) -> Unit = { elSyncStatus = it }
    val config =
      ELSyncService.Config(
        pollingInterval = 1.seconds,
        leeway = 0u,
      )
    val timer = PeerChainTrackerTest.TestableTimer()
    val beaconChain = mock<BeaconChain>()
    whenever(beaconChain.getLatestBeaconState()).thenReturn(DataGenerators.randomBeaconState(0UL))
    val executionLayerManager = mock<ExecutionLayerManager>()

    val elSyncService =
      ELSyncService(
        beaconChain = beaconChain,
        executionLayerManager = executionLayerManager,
        onStatusChange = onStatusChange,
        config = config,
        timerFactory = { _, _ -> timer },
      )

    elSyncService.start()
    assertThat(elSyncStatus).isNull()
    timer.runNextTask()

    assertThat(elSyncStatus).isEqualTo(ELSyncStatus.SYNCED)
    elSyncService.stop()
  }

  @Test
  fun `should change el sync status when el is syncing and synced`() {
    var elSyncStatus: ELSyncStatus? = null
    val onStatusChange: (ELSyncStatus) -> Unit = { elSyncStatus = it }
    val config =
      ELSyncService.Config(
        pollingInterval = 1.seconds,
        leeway = 1u,
      )
    val timer = PeerChainTrackerTest.TestableTimer()
    val beaconChain = mock<BeaconChain>()
    whenever(beaconChain.getLatestBeaconState()).thenReturn(DataGenerators.randomBeaconState(0UL))
    val executionLayerManager = mock<ExecutionLayerManager>()

    val elSyncService =
      ELSyncService(
        beaconChain = beaconChain,
        executionLayerManager = executionLayerManager,
        onStatusChange = onStatusChange,
        config = config,
        timerFactory = { _, _ -> timer },
      )

    elSyncService.start()
    assertThat(elSyncStatus).isNull()
    timer.runNextTask()
    assertThat(elSyncStatus).isEqualTo(ELSyncStatus.SYNCED)

    whenever(beaconChain.getLatestBeaconState()).thenReturn(DataGenerators.randomBeaconState(3UL))
    val sealedBlock = DataGenerators.randomSealedBeaconBlock(2UL)
    whenever(beaconChain.getSealedBeaconBlock(2uL)).thenReturn(sealedBlock)
    whenever(executionLayerManager.setHead(any(), any(), any()))
      .thenReturn(
        SafeFuture.completedFuture(
          ForkChoiceUpdatedResult(PayloadStatus(ExecutionPayloadStatus.SYNCING, null, null), null),
        ),
      )
    timer.runNextTask()
    assertThat(elSyncStatus).isEqualTo(ELSyncStatus.SYNCING)

    whenever(executionLayerManager.setHead(any(), any(), any()))
      .thenReturn(
        SafeFuture.completedFuture(
          ForkChoiceUpdatedResult(PayloadStatus(ExecutionPayloadStatus.VALID, null, null), null),
        ),
      )
    timer.runNextTask()
    assertThat(elSyncStatus).isEqualTo(ELSyncStatus.SYNCED)
    elSyncService.stop()
  }
}
